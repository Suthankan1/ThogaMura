package lk.thogamura.networkserver.fulfillment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.inventory.MonotonicVersionProvider;
import lk.thogamura.networkserver.inventory.PayloadFingerprintCalculator;
import lk.thogamura.networkserver.notifications.NotificationPublisher;
import lk.thogamura.networkserver.persistence.CommandReceipt;
import lk.thogamura.networkserver.persistence.CommandReceiptRepository;
import lk.thogamura.networkserver.persistence.ProductStock;
import lk.thogamura.networkserver.persistence.ProductStockRepository;
import lk.thogamura.networkserver.persistence.StockMovementRecord;
import lk.thogamura.networkserver.persistence.StockMovementRepository;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.networkserver.persistence.StockRequestRepository;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Default transactional implementation of {@link StockFulfillmentService}.
 *
 * <p>Enforces the immutable fulfillment state machine:
 * {@code ACCEPTED -> PICKED -> DISPATCHED}.
 *
 * <p>Key guarantees:
 * <ul>
 *   <li>On dispatch, stock balances ({@code on_hand} and {@code reserved}) and exactly one
 *       movement audit record are mutated within a single database transaction.</li>
 *   <li>Durable idempotency receipts ensure replayed commands return identical outcomes
 *       without re-executing inventory calculations or logging duplicate movements.</li>
 *   <li>Version-only Java NIO invalidation events are published strictly post-commit.
 *       Notification broadcaster failure never rolls back committed transactions.</li>
 * </ul>
 */
@Service
public class DefaultStockFulfillmentService implements StockFulfillmentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultStockFulfillmentService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StockRequestRepository stockRequestRepository;
    private final ProductStockRepository productStockRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CommandReceiptRepository commandReceiptRepository;
    private final Optional<NotificationPublisher> notificationPublisher;
    private final MonotonicVersionProvider versionProvider;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public DefaultStockFulfillmentService(
            StockRequestRepository stockRequestRepository,
            ProductStockRepository productStockRepository,
            StockMovementRepository stockMovementRepository,
            CommandReceiptRepository commandReceiptRepository,
            @Autowired(required = false) Optional<NotificationPublisher> notificationPublisher,
            MonotonicVersionProvider versionProvider,
            PlatformTransactionManager transactionManager
    ) {
        this.stockRequestRepository = Objects.requireNonNull(stockRequestRepository, "stockRequestRepository must not be null");
        this.productStockRepository = Objects.requireNonNull(productStockRepository, "productStockRepository must not be null");
        this.stockMovementRepository = Objects.requireNonNull(stockMovementRepository, "stockMovementRepository must not be null");
        this.commandReceiptRepository = Objects.requireNonNull(commandReceiptRepository, "commandReceiptRepository must not be null");
        this.notificationPublisher = notificationPublisher != null ? notificationPublisher : Optional.empty();
        this.versionProvider = Objects.requireNonNull(versionProvider, "versionProvider must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");

        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public FulfillmentResult pickStock(UUID commandRequestId, UUID stockRequestId) {
        Objects.requireNonNull(commandRequestId, "commandRequestId must not be null");
        Objects.requireNonNull(stockRequestId, "stockRequestId must not be null");

        String canonicalFingerprint = PayloadFingerprintCalculator.calculateStockRequestIdFingerprint(stockRequestId);

        // 1. Check existing receipt for deterministic replay
        Optional<CommandReceipt> existingReceipt = commandReceiptRepository.findByCommandRequestId(commandRequestId);
        if (existingReceipt.isPresent()) {
            return handleExistingReceipt(existingReceipt.get(), commandRequestId, ProtocolInfo.COMMAND_MARK_PICKED, canonicalFingerprint, stockRequestId);
        }

        // 2. Execute transactional state transition
        try {
            return transactionTemplate.execute(status -> {
                Optional<CommandReceipt> receiptUnderTx = commandReceiptRepository.findByCommandRequestId(commandRequestId);
                if (receiptUnderTx.isPresent()) {
                    return handleExistingReceipt(receiptUnderTx.get(), commandRequestId, ProtocolInfo.COMMAND_MARK_PICKED, canonicalFingerprint, stockRequestId);
                }

                // Row-level lock on stock request
                Optional<StockRequestRecord> requestOpt = stockRequestRepository.findByIdForUpdate(stockRequestId);
                if (requestOpt.isEmpty()) {
                    String msg = "Stock request '" + stockRequestId + "' not found";
                    String errJson = TcpMessageCodec.formatErrorResponse(commandRequestId.toString(), ProtocolInfo.ERROR_NOT_FOUND, msg);
                    log.warn("Fulfillment pick failed: {}", msg);
                    return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_NOT_FOUND, msg, errJson);
                }

                StockRequestRecord req = requestOpt.get();
                if (!StockRequestRecord.STATUS_ACCEPTED.equals(req.status())) {
                    String msg = "Cannot transition stock request '" + stockRequestId + "' from " + req.status()
                            + " to PICKED; only ACCEPTED requests can be picked";
                    String errJson = TcpMessageCodec.formatErrorResponse(commandRequestId.toString(), ProtocolInfo.ERROR_INVALID_TRANSITION, msg);
                    log.warn("Illegal state transition rejected: {}", msg);
                    return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_INVALID_TRANSITION, msg, errJson);
                }

                // Transition status to PICKED
                stockRequestRepository.updateStatus(stockRequestId, StockRequestRecord.STATUS_PICKED);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("stockRequestId", stockRequestId.toString());
                data.put("status", ProtocolInfo.STATUS_PICKED);
                String resultJson = TcpMessageCodec.formatResultResponse(commandRequestId.toString(), ProtocolInfo.STATUS_PICKED, data);

                CommandReceipt receipt = CommandReceipt.of(commandRequestId, ProtocolInfo.COMMAND_MARK_PICKED, canonicalFingerprint, resultJson);
                commandReceiptRepository.insert(receipt);

                log.info("Stock request '{}' successfully transitioned to PICKED (commandRequestId: '{}')",
                        stockRequestId, commandRequestId);

                registerPostCommitNotification();

                return FulfillmentResult.success(commandRequestId, stockRequestId, ProtocolInfo.STATUS_PICKED, false, resultJson);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent insert conflict on commandRequestId '{}', checking receipt: {}", commandRequestId, e.getMessage());
            Optional<CommandReceipt> winningReceipt = commandReceiptRepository.findByCommandRequestId(commandRequestId);
            if (winningReceipt.isPresent()) {
                return handleExistingReceipt(winningReceipt.get(), commandRequestId, ProtocolInfo.COMMAND_MARK_PICKED, canonicalFingerprint, stockRequestId);
            }
            throw e;
        }
    }

    @Override
    public FulfillmentResult dispatchStock(UUID commandRequestId, UUID stockRequestId) {
        Objects.requireNonNull(commandRequestId, "commandRequestId must not be null");
        Objects.requireNonNull(stockRequestId, "stockRequestId must not be null");

        String canonicalFingerprint = PayloadFingerprintCalculator.calculateStockRequestIdFingerprint(stockRequestId);

        // 1. Check existing receipt for deterministic replay
        Optional<CommandReceipt> existingReceipt = commandReceiptRepository.findByCommandRequestId(commandRequestId);
        if (existingReceipt.isPresent()) {
            return handleExistingReceipt(existingReceipt.get(), commandRequestId, ProtocolInfo.COMMAND_MARK_DISPATCHED, canonicalFingerprint, stockRequestId);
        }

        // 2. Execute transactional dispatch
        try {
            return transactionTemplate.execute(status -> {
                Optional<CommandReceipt> receiptUnderTx = commandReceiptRepository.findByCommandRequestId(commandRequestId);
                if (receiptUnderTx.isPresent()) {
                    return handleExistingReceipt(receiptUnderTx.get(), commandRequestId, ProtocolInfo.COMMAND_MARK_DISPATCHED, canonicalFingerprint, stockRequestId);
                }

                // Row-level lock on stock request
                Optional<StockRequestRecord> requestOpt = stockRequestRepository.findByIdForUpdate(stockRequestId);
                if (requestOpt.isEmpty()) {
                    String msg = "Stock request '" + stockRequestId + "' not found";
                    String errJson = TcpMessageCodec.formatErrorResponse(commandRequestId.toString(), ProtocolInfo.ERROR_NOT_FOUND, msg);
                    log.warn("Fulfillment dispatch failed: {}", msg);
                    return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_NOT_FOUND, msg, errJson);
                }

                StockRequestRecord req = requestOpt.get();
                if (!StockRequestRecord.STATUS_PICKED.equals(req.status())) {
                    String msg = "Cannot transition stock request '" + stockRequestId + "' from " + req.status()
                            + " to DISPATCHED; only PICKED requests can be dispatched";
                    String errJson = TcpMessageCodec.formatErrorResponse(commandRequestId.toString(), ProtocolInfo.ERROR_INVALID_TRANSITION, msg);
                    log.warn("Illegal state transition rejected: {}", msg);
                    return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_INVALID_TRANSITION, msg, errJson);
                }

                // Row-level lock on product stock row
                Optional<ProductStock> stockOpt = productStockRepository.findBySkuForUpdate(req.sku());
                if (stockOpt.isEmpty()) {
                    String msg = "Product SKU '" + req.sku() + "' not found for stock request '" + stockRequestId + "'";
                    String errJson = TcpMessageCodec.formatErrorResponse(commandRequestId.toString(), ProtocolInfo.ERROR_NOT_FOUND, msg);
                    return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_NOT_FOUND, msg, errJson);
                }

                ProductStock currentStock = stockOpt.get();
                int quantity = req.quantity();
                if (currentStock.reserved() < quantity || currentStock.onHand() < quantity) {
                    String msg = "Cannot dispatch: requested quantity " + quantity + " exceeds onHand="
                            + currentStock.onHand() + " or reserved=" + currentStock.reserved();
                    String errJson = TcpMessageCodec.formatErrorResponse(commandRequestId.toString(), ProtocolInfo.ERROR_INSUFFICIENT_STOCK, msg);
                    log.error("Stock balance inconsistency on SKU '{}': {}", req.sku(), msg);
                    return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_INSUFFICIENT_STOCK, msg, errJson);
                }

                // Atomically decrement on_hand and reserved
                int newOnHand = currentStock.onHand() - quantity;
                int newReserved = currentStock.reserved() - quantity;
                productStockRepository.updateBalances(req.sku(), newOnHand, newReserved);

                // Update stock request status to DISPATCHED
                stockRequestRepository.updateStatus(stockRequestId, StockRequestRecord.STATUS_DISPATCHED);

                // Write immutable stock movement record (unique per stockRequestId)
                StockMovementRecord movement = StockMovementRecord.dispatch(stockRequestId, req.sku(), quantity);
                stockMovementRepository.insert(movement);

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("stockRequestId", stockRequestId.toString());
                data.put("status", ProtocolInfo.STATUS_DISPATCHED);
                String resultJson = TcpMessageCodec.formatResultResponse(commandRequestId.toString(), ProtocolInfo.STATUS_DISPATCHED, data);

                CommandReceipt receipt = CommandReceipt.of(commandRequestId, ProtocolInfo.COMMAND_MARK_DISPATCHED, canonicalFingerprint, resultJson);
                commandReceiptRepository.insert(receipt);

                log.info("Dispatched stock request '{}' for SKU '{}', quantity={}, newOnHand={}, newReserved={}, available={}",
                        stockRequestId, req.sku(), quantity, newOnHand, newReserved, (newOnHand - newReserved));

                registerPostCommitNotification();

                return FulfillmentResult.success(commandRequestId, stockRequestId, ProtocolInfo.STATUS_DISPATCHED, false, resultJson);
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent insert conflict on commandRequestId '{}', checking receipt: {}", commandRequestId, e.getMessage());
            Optional<CommandReceipt> winningReceipt = commandReceiptRepository.findByCommandRequestId(commandRequestId);
            if (winningReceipt.isPresent()) {
                return handleExistingReceipt(winningReceipt.get(), commandRequestId, ProtocolInfo.COMMAND_MARK_DISPATCHED, canonicalFingerprint, stockRequestId);
            }
            throw e;
        }
    }

    @Override
    public List<StockRequestRecord> getRequests(Optional<String> statusFilter) {
        if (statusFilter != null && statusFilter.isPresent() && !statusFilter.get().isBlank()) {
            return stockRequestRepository.findByStatus(statusFilter.get().trim().toUpperCase());
        }
        return stockRequestRepository.findAll();
    }

    @Override
    public List<StockMovementRecord> getMovements() {
        return stockMovementRepository.findAll();
    }

    private FulfillmentResult handleExistingReceipt(
            CommandReceipt receipt,
            UUID commandRequestId,
            String expectedCommandType,
            String expectedFingerprint,
            UUID stockRequestId
    ) {
        boolean typeMatches = expectedCommandType.equals(receipt.commandType());
        boolean fingerprintMatches = receipt.payloadFingerprint().equals(expectedFingerprint);

        if (typeMatches && fingerprintMatches) {
            log.info("Durable command receipt replayed for requestId '{}'", commandRequestId);
            String status = expectedCommandType.equals(ProtocolInfo.COMMAND_MARK_PICKED)
                    ? ProtocolInfo.STATUS_PICKED
                    : ProtocolInfo.STATUS_DISPATCHED;
            return FulfillmentResult.success(commandRequestId, stockRequestId, status, true, receipt.resultJson());
        } else {
            String msg = "Command requestId '" + commandRequestId + "' was previously processed with a different payload or command type";
            String conflictJson = TcpMessageCodec.formatErrorResponse(
                    commandRequestId.toString(), ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT, msg
            );
            log.warn("Command conflict detected for requestId '{}': expected fingerprint {}, recorded {}",
                    commandRequestId, expectedFingerprint, receipt.payloadFingerprint());
            return FulfillmentResult.error(commandRequestId, stockRequestId, ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT, msg, conflictJson);
        }
    }

    private void registerPostCommitNotification() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
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
                log.warn("Best-effort Java NIO notification failed (fulfillment remains committed): {}", e.getMessage(), e);
            }
        });
    }
}
