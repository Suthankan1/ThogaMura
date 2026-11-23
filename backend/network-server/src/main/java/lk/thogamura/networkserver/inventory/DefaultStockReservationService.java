package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.networkserver.notifications.NotificationPublisher;
import lk.thogamura.networkserver.persistence.CommandReceipt;
import lk.thogamura.networkserver.persistence.CommandReceiptRepository;
import lk.thogamura.networkserver.persistence.ProductStock;
import lk.thogamura.networkserver.persistence.ProductStockRepository;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.networkserver.persistence.StockRequestRepository;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.MalformedFrameException;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authoritative stock reservation service implementing atomic database transactions,
 * deterministic durable command receipts, and post-commit NIO notifications.
 *
 * <p>Enforces core inventory invariant:
 * {@code available = on_hand - reserved >= 0}
 * and frozen Protocol v1 command receipt replay semantics.</p>
 */
@Service
public class DefaultStockReservationService implements StockReservationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultStockReservationService.class);
    private static final ObjectMapper OBJECT_MAPPER = TcpMessageCodec.getObjectMapper();

    private final ProductStockRepository productStockRepository;
    private final StockRequestRepository stockRequestRepository;
    private final CommandReceiptRepository commandReceiptRepository;
    private final Optional<NotificationPublisher> notificationPublisher;
    private final MonotonicVersionProvider versionProvider;
    private final TransactionTemplate transactionTemplate;

    public DefaultStockReservationService(
            ProductStockRepository productStockRepository,
            StockRequestRepository stockRequestRepository,
            CommandReceiptRepository commandReceiptRepository,
            Optional<NotificationPublisher> notificationPublisher,
            MonotonicVersionProvider versionProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.productStockRepository = Objects.requireNonNull(productStockRepository, "productStockRepository must not be null");
        this.stockRequestRepository = Objects.requireNonNull(stockRequestRepository, "stockRequestRepository must not be null");
        this.commandReceiptRepository = Objects.requireNonNull(commandReceiptRepository, "commandReceiptRepository must not be null");
        this.notificationPublisher = Objects.requireNonNull(notificationPublisher, "notificationPublisher must not be null");
        this.versionProvider = Objects.requireNonNull(versionProvider, "versionProvider must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public ReservationResult reserveStock(ReservationCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        UUID requestId = command.requestId();
        String sku = command.sku();
        int quantity = command.quantity();

        // 1. Validate positive quantity
        if (quantity <= 0) {
            String msg = "Requested quantity must be positive, but was " + quantity;
            String errorJson = TcpMessageCodec.formatErrorResponse(requestId.toString(), ProtocolInfo.ERROR_VALIDATION, msg);
            return ReservationResult.validationError(requestId, msg, errorJson);
        }

        // 2. Validate non-empty SKU
        if (sku == null || sku.isBlank()) {
            String msg = "Product SKU must not be null or blank";
            String errorJson = TcpMessageCodec.formatErrorResponse(requestId.toString(), ProtocolInfo.ERROR_VALIDATION, msg);
            return ReservationResult.validationError(requestId, msg, errorJson);
        }

        String baseFingerprint = PayloadFingerprintCalculator.calculateStockRequestFingerprint(sku, quantity);
        String canonicalFingerprint = command.branchId() == null ? baseFingerprint
                : PayloadFingerprintCalculator.computeSha256Hex(command.branchId() + ":" + baseFingerprint);

        // 3. Fast check for existing durable command receipt (outside transaction)
        Optional<CommandReceipt> existingReceipt = commandReceiptRepository.findByCommandRequestId(requestId);
        if (existingReceipt.isPresent()) {
            return handleExistingReceipt(existingReceipt.get(), requestId, canonicalFingerprint, sku);
        }

        // 4. Execute atomic database transaction
        try {
            return transactionTemplate.execute(status -> {
                // Double check under transaction in case a concurrent request committed
                Optional<CommandReceipt> receiptUnderTx = commandReceiptRepository.findByCommandRequestId(requestId);
                if (receiptUnderTx.isPresent()) {
                    return handleExistingReceipt(receiptUnderTx.get(), requestId, canonicalFingerprint, sku);
                }

                // Lock row with SELECT ... FOR UPDATE
                Optional<ProductStock> stockOpt = productStockRepository.findBySkuForUpdate(sku);
                if (stockOpt.isEmpty()) {
                    String msg = "Product SKU '" + sku + "' not found";
                    String errorJson = TcpMessageCodec.formatErrorResponse(requestId.toString(), ProtocolInfo.ERROR_NOT_FOUND, msg);
                    return ReservationResult.notFound(requestId, msg, errorJson);
                }

                ProductStock currentStock = stockOpt.get();
                int available = currentStock.available();

                if (available >= quantity) {
                    // ACCEPTED: Sufficient stock
                    int newReserved = currentStock.reserved() + quantity;
                    productStockRepository.updateReserved(sku, newReserved);

                    StockRequestRecord requestRecord = StockRequestRecord.accepted(
                            requestId, canonicalFingerprint, sku, quantity
                    ).forBranch(command.branchId());
                    stockRequestRepository.insert(requestRecord);

                    ProductStock updatedStock = new ProductStock(
                            currentStock.sku(),
                            currentStock.name(),
                            currentStock.onHand(),
                            newReserved
                    );

                    String acceptedJson = formatAcceptedResponse(requestId, sku, quantity);

                    CommandReceipt receipt = CommandReceipt.of(
                            requestId,
                            ProtocolInfo.COMMAND_REQUEST_STOCK,
                            canonicalFingerprint,
                            acceptedJson
                    );
                    commandReceiptRepository.insert(receipt);

                    // Register post-commit notification trigger
                    registerPostCommitNotification();

                    log.info("Reserved stock for SKU '{}', requested={}, newReserved={}, available={}",
                            sku, quantity, newReserved, updatedStock.available());

                    return ReservationResult.accepted(
                            requestId, updatedStock, requestRecord, false, acceptedJson
                    );
                } else {
                    // REJECTED: Insufficient available stock
                    String reason = "Requested quantity " + quantity + " exceeds available stock " + available;
                    StockRequestRecord requestRecord = StockRequestRecord.rejected(
                            requestId, canonicalFingerprint, sku, quantity, reason
                    ).forBranch(command.branchId());
                    stockRequestRepository.insert(requestRecord);

                    String rejectedJson = formatRejectionResponse(
                            requestId, ProtocolInfo.ERROR_INSUFFICIENT_STOCK, reason
                    );

                    CommandReceipt receipt = CommandReceipt.of(
                            requestId,
                            ProtocolInfo.COMMAND_REQUEST_STOCK,
                            canonicalFingerprint,
                            rejectedJson
                    );
                    commandReceiptRepository.insert(receipt);

                    // Register post-commit notification trigger for rejected request (#59)
                    registerPostCommitNotification();

                    log.info("Rejected stock reservation for SKU '{}', requested={}, available={}",
                            sku, quantity, available);

                    return ReservationResult.rejected(
                            requestId, ProtocolInfo.ERROR_INSUFFICIENT_STOCK, reason, requestRecord, false, rejectedJson
                    );
                }
            });
        } catch (DataIntegrityViolationException e) {
            // Handle concurrent duplicate delivery where another thread committed the same requestId
            log.info("Concurrent insert conflict on requestId '{}', checking committed receipt: {}", requestId, e.getMessage());
            Optional<CommandReceipt> winningReceipt = commandReceiptRepository.findByCommandRequestId(requestId);
            if (winningReceipt.isPresent()) {
                return handleExistingReceipt(winningReceipt.get(), requestId, canonicalFingerprint, sku);
            }
            throw e;
        }
    }

    @Override
    public ReservationResult processReservationCommand(String rawFrameJson) {
        Objects.requireNonNull(rawFrameJson, "rawFrameJson must not be null");
        try {
            JsonNode root = TcpMessageCodec.parseJsonObject(rawFrameJson);
            TcpMessageCodec.validateEnvelope(root);

            String commandType = root.get("type").asText();
            if (!ProtocolInfo.COMMAND_REQUEST_STOCK.equals(commandType)) {
                String reqId = root.hasNonNull("requestId") ? root.get("requestId").asText() : UUID.randomUUID().toString();
                String msg = "Unsupported command type: " + commandType + " (expected " + ProtocolInfo.COMMAND_REQUEST_STOCK + ")";
                String errJson = TcpMessageCodec.formatErrorResponse(reqId, ProtocolInfo.ERROR_VALIDATION, msg);
                return ReservationResult.validationError(UUID.fromString(reqId), msg, errJson);
            }

            UUID requestId = UUID.fromString(root.get("requestId").asText().trim());
            JsonNode dataNode = root.get("data");
            if (dataNode == null || !dataNode.isObject()) {
                String msg = "Missing required 'data' object in REQUEST_STOCK frame";
                String errJson = TcpMessageCodec.formatErrorResponse(requestId.toString(), ProtocolInfo.ERROR_VALIDATION, msg);
                return ReservationResult.validationError(requestId, msg, errJson);
            }

            if (!dataNode.hasNonNull("sku") || !dataNode.get("sku").isTextual() || dataNode.get("sku").asText().isBlank()) {
                String msg = "Missing required field 'sku' in command data";
                String errJson = TcpMessageCodec.formatErrorResponse(requestId.toString(), ProtocolInfo.ERROR_VALIDATION, msg);
                return ReservationResult.validationError(requestId, msg, errJson);
            }
            String sku = dataNode.get("sku").asText().trim();

            if (!dataNode.has("quantity") || !dataNode.get("quantity").isInt()) {
                String msg = "Missing or non-integer field 'quantity' in command data";
                String errJson = TcpMessageCodec.formatErrorResponse(requestId.toString(), ProtocolInfo.ERROR_VALIDATION, msg);
                return ReservationResult.validationError(requestId, msg, errJson);
            }
            int quantity = dataNode.get("quantity").asInt();

            return reserveStock(new ReservationCommand(requestId, sku, quantity));
        } catch (MalformedFrameException e) {
            String reqId = e.getRequestId() != null ? e.getRequestId() : UUID.randomUUID().toString();
            String errJson = TcpMessageCodec.formatErrorResponse(reqId, e.getErrorCode(), e.getMessage());
            return ReservationResult.validationError(UUID.fromString(reqId), e.getMessage(), errJson);
        } catch (Exception e) {
            String reqId = UUID.randomUUID().toString();
            String errJson = TcpMessageCodec.formatErrorResponse(reqId, ProtocolInfo.ERROR_INTERNAL, e.getMessage());
            return ReservationResult.validationError(UUID.fromString(reqId), e.getMessage(), errJson);
        }
    }

    private ReservationResult handleExistingReceipt(
            CommandReceipt receipt,
            UUID requestId,
            String expectedFingerprint,
            String sku
    ) {
        boolean typeMatches = ProtocolInfo.COMMAND_REQUEST_STOCK.equals(receipt.commandType());
        boolean fingerprintMatches = receipt.payloadFingerprint().equals(expectedFingerprint);

        if (typeMatches && fingerprintMatches) {
            // Replay: Exactly matches previous command and payload
            log.info("Durable command receipt replayed for requestId '{}'", requestId);
            return parseReplayedReceipt(receipt, requestId, sku);
        } else {
            // Conflict: Same requestId reused with different payload or command type
            String msg = "Command requestId '" + requestId + "' was previously processed with a different payload or command type";
            String conflictJson = TcpMessageCodec.formatErrorResponse(
                    requestId.toString(), ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT, msg
            );
            log.warn("Command conflict detected for requestId '{}': expected fingerprint {}, recorded {}",
                    requestId, expectedFingerprint, receipt.payloadFingerprint());
            return ReservationResult.conflict(requestId, msg, conflictJson);
        }
    }

    private ReservationResult parseReplayedReceipt(CommandReceipt receipt, UUID requestId, String sku) {
        String rawResultJson = receipt.resultJson();
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawResultJson);
            // Compact canonical JSON string without external formatting discrepancies
            String resultJson = OBJECT_MAPPER.writeValueAsString(root);

            String status = root.path("status").asText(ProtocolInfo.STATUS_ACCEPTED);
            StockRequestRecord record = stockRequestRepository.findById(requestId).orElse(null);

            if (ProtocolInfo.STATUS_ACCEPTED.equals(status)) {
                ProductStock stock = productStockRepository.findBySku(sku).orElse(null);
                return ReservationResult.accepted(requestId, stock, record, true, resultJson);
            } else if (ProtocolInfo.STATUS_REJECTED.equals(status)) {
                String errCode = root.path("error").path("code").asText(ProtocolInfo.ERROR_INSUFFICIENT_STOCK);
                String errMsg = root.path("error").path("message").asText("Requested quantity exceeds available stock");
                return ReservationResult.rejected(requestId, errCode, errMsg, record, true, resultJson);
            } else {
                String errCode = root.path("error").path("code").asText(ProtocolInfo.ERROR_INTERNAL);
                String errMsg = root.path("error").path("message").asText();
                return new ReservationResult(
                        requestId, status, true, resultJson, errCode, errMsg, record, null
                );
            }
        } catch (JsonProcessingException e) {
            // Fallback if parsing cached JSON fails
            return new ReservationResult(
                    requestId, ProtocolInfo.STATUS_ACCEPTED, true, rawResultJson, null, null, null, null
            );
        }
    }

    private void registerPostCommitNotification() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishChangeNotificationSafely();
                }
            });
        } else {
            publishChangeNotificationSafely();
        }
    }

    private void publishChangeNotificationSafely() {
        notificationPublisher.ifPresent(publisher -> {
            try {
                long version = versionProvider.nextVersion();
                publisher.publish(version);
                log.info("Published Java NIO cache invalidation signal for change version {}", version);
            } catch (Exception e) {
                log.warn("Best-effort Java NIO notification failed (stock reservation remains committed): {}", e.getMessage(), e);
            }
        });
    }

    private String formatAcceptedResponse(UUID requestId, String sku, int quantity) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("requestId", requestId.toString());
        data.put("sku", sku);
        data.put("quantity", quantity);
        data.put("status", ProtocolInfo.STATUS_ACCEPTED);

        return TcpMessageCodec.formatResultResponse(requestId.toString(), ProtocolInfo.STATUS_ACCEPTED, data);
    }

    private String formatRejectionResponse(UUID requestId, String errorCode, String message) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("v", ProtocolInfo.WIRE_VERSION);
        root.put("type", ProtocolInfo.TYPE_RESULT);
        root.put("requestId", requestId.toString());
        root.put("status", ProtocolInfo.STATUS_REJECTED);

        ObjectNode err = root.putObject("error");
        err.put("code", errorCode);
        err.put("message", message);

        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize rejection response", e);
        }
    }
}
