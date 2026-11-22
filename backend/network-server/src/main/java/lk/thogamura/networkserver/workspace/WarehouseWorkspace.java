package lk.thogamura.networkserver.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.networkserver.fulfillment.DefaultStockFulfillmentService;
import lk.thogamura.networkserver.integration.supplier.SupplierCommandHandler;
import lk.thogamura.networkserver.inventory.MonotonicVersionProvider;
import lk.thogamura.networkserver.inventory.PayloadFingerprintCalculator;
import lk.thogamura.networkserver.inventory.ReservationCommand;
import lk.thogamura.networkserver.inventory.StockReservationService;
import lk.thogamura.networkserver.notifications.NotificationPublisher;
import lk.thogamura.networkserver.persistence.CommandReceipt;
import lk.thogamura.networkserver.persistence.CommandReceiptRepository;
import lk.thogamura.networkserver.persistence.ProductStock;
import lk.thogamura.networkserver.persistence.ProductStockRepository;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.networkserver.persistence.StockRequestRepository;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.access.WorkspaceAccess;
import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import lk.thogamura.protocol.access.WorkspaceAccess.Role;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@ConditionalOnProperty(name = "thogamura.access.enabled", havingValue = "true")
public class WarehouseWorkspace {
    private static final Logger log = LoggerFactory.getLogger(WarehouseWorkspace.class);
    private static final int VERSION = WorkspaceAccess.VERSION;
    private static final Set<String> STATUSES = Set.of("", "ACCEPTED", "REJECTED", "PICKED", "DISPATCHED", "CANCELLED");

    private final WorkspaceAccess access;
    private final JdbcTemplate jdbc;
    private final WorkspaceQueries queries;
    private final TransactionTemplate transaction;
    private final StockRequestRepository requests;
    private final ProductStockRepository stock;
    private final CommandReceiptRepository receipts;
    private final StockReservationService reservation;
    private final DefaultStockFulfillmentService fulfillment;
    private final Optional<SupplierCommandHandler> supplier;
    private final Optional<NotificationPublisher> notifications;
    private final MonotonicVersionProvider versions;

    public WarehouseWorkspace(@Value("${thogamura.access.signing-key}") String key,
                              @Value("${thogamura.tls.server.enabled}") boolean tls,
                              JdbcTemplate jdbc, PlatformTransactionManager manager, StockRequestRepository requests,
                              ProductStockRepository stock, CommandReceiptRepository receipts,
                              StockReservationService reservation, DefaultStockFulfillmentService fulfillment,
                              Optional<SupplierCommandHandler> supplier, Optional<NotificationPublisher> notifications,
                              MonotonicVersionProvider versions) {
        if (!tls) throw new IllegalStateException("Workspace requires a TLS command listener");
        access = new WorkspaceAccess(key);
        this.jdbc = jdbc;
        queries = new WorkspaceQueries(jdbc);
        transaction = new TransactionTemplate(manager);
        this.requests = requests;
        this.stock = stock;
        this.receipts = receipts;
        this.reservation = reservation;
        this.fulfillment = fulfillment;
        this.supplier = supplier;
        this.notifications = notifications;
        this.versions = versions;
    }

    public String dispatch(JsonNode request) {
        String id = request.path("requestId").asText();
        String type = request.path("type").asText();
        JsonNode envelope = request.path("data");
        Actor actor = access.verify(type, id, envelope);
        if (actor == null) return error(id, WorkspaceAccess.ERROR_FORBIDDEN, "Workspace assertion is invalid");
        try {
            return execute(uuid(id), type, actor, envelope.path("args"));
        } catch (WorkspaceFailure failure) {
            return error(id, failure.code, failure.getMessage());
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            return error(id, ProtocolInfo.ERROR_VALIDATION, "Invalid workspace arguments or cursor");
        } catch (Exception failure) {
            log.error("Workspace command {} failed for {}", type, id, failure);
            return error(id, ProtocolInfo.ERROR_INTERNAL, "Warehouse could not process the workspace command");
        }
    }

    private String execute(UUID id, String type, Actor actor, JsonNode args) throws JsonProcessingException {
        return switch (type) {
            case ProtocolInfo.COMMAND_GET_PRODUCTS -> accepted(id, queries.products(size(args), cursor(args), search(args)));
            case ProtocolInfo.COMMAND_GET_REQUESTS -> accepted(id, queries.requests(actor, size(args), cursor(args), status(args)));
            case WorkspaceAccess.GET_REQUEST -> {
                Map<String, Object> row = queries.request(actor, requestId(args));
                if (row == null) throw missingRequest();
                yield accepted(id, row);
            }
            case ProtocolInfo.COMMAND_REQUEST_STOCK -> {
                require(actor.role() == Role.OPERATOR);
                yield upgrade(reservation.reserveStock(new ReservationCommand(id, sku(args), quantity(args), actor.branch())).responseJson());
            }
            case ProtocolInfo.COMMAND_MARK_PICKED -> {
                require(actor.staff());
                yield upgrade(fulfillment.pickStock(id, requestId(args), true).responseJson());
            }
            case ProtocolInfo.COMMAND_MARK_DISPATCHED -> {
                require(actor.staff());
                yield upgrade(fulfillment.dispatchStock(id, requestId(args), true).responseJson());
            }
            case WorkspaceAccess.CANCEL -> {
                UUID target = requestId(args);
                yield mutate(id, type, actor, target.toString(), () -> {
                    StockRequestRecord row = requests.findByIdForUpdate(target).orElseThrow(WarehouseWorkspace::missingRequest);
                    if (row.branchId() == null || (!actor.staff() && !actor.branch().equals(row.branchId()))) {
                        throw missingRequest();
                    }
                    ProductStock product = stock.findBySkuForUpdate(row.sku()).orElseThrow();
                    return () -> cancel(row, product);
                });
            }
            case WorkspaceAccess.RECEIVE -> {
                require(actor.role() == Role.SUPERVISOR);
                String sku = sku(args);
                int quantity = quantity(args);
                String note = field(args, "note", 160, false).strip();
                if (note.isEmpty() || note.codePoints().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Invalid receiving note");
                }
                yield mutate(id, type, actor, sku + "\n" + quantity + "\n" + note, () -> {
                    ProductStock product = stock.findBySkuForUpdate(sku)
                            .orElseThrow(() -> new WorkspaceFailure(ProtocolInfo.ERROR_NOT_FOUND, "SKU was not found"));
                    return () -> receive(id, actor, product, quantity, note);
                });
            }
            case ProtocolInfo.COMMAND_GET_MOVEMENTS -> {
                require(actor.role() == Role.SUPERVISOR);
                yield accepted(id, queries.movements(size(args), cursor(args)));
            }
            case ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK -> {
                SupplierCommandHandler handler = supplier.orElseThrow(
                        () -> new WorkspaceFailure(ProtocolInfo.ERROR_NOT_FOUND, "Supplier connector is unavailable"));
                ObjectNode command = TcpMessageCodec.getObjectMapper().createObjectNode().put("requestId", id.toString());
                command.set("data", TcpMessageCodec.getObjectMapper().valueToTree(Map.of("sku", sku(args))));
                yield upgrade(handler.handle(command));
            }
            default -> error(id.toString(), ProtocolInfo.ERROR_VALIDATION, "Unknown workspace command");
        };
    }

    private String mutate(UUID id, String type, Actor actor, String payload, Supplier<Supplier<Map<String, Object>>> lockTarget) {
        String fingerprint = PayloadFingerprintCalculator.computeSha256Hex(
                "workspace\n" + type + "\n" + actor.branch() + "\n" + actor.name() + "\n" + actor.role() + "\n" + payload);
        return transaction.execute(status -> {
            var prior = receipts.findByCommandRequestId(id);
            if (prior.isPresent()) return replay(prior.get(), type, fingerprint);
            // Match the reservation and fulfillment lock order before claiming the shared receipt key.
            Supplier<Map<String, Object>> change = lockTarget.get();
            int claimed = jdbc.update("""
                    INSERT INTO command_receipts (command_request_id, command_type, payload_fingerprint, result_json)
                    VALUES (?, ?, ?, '{}'::jsonb) ON CONFLICT (command_request_id) DO NOTHING
                    """, id, type, fingerprint);
            if (claimed == 0) {
                return replay(receipts.findByCommandRequestId(id).orElseThrow(), type, fingerprint);
            }
            String result = accepted(id, change.get());
            jdbc.update("UPDATE command_receipts SET result_json = ?::jsonb WHERE command_request_id = ?", result, id);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifications.ifPresent(publisher -> {
                        try {
                            publisher.publish(versions.nextVersion());
                        } catch (RuntimeException failure) {
                            log.warn("Workspace transaction committed but the change hint failed", failure);
                        }
                    });
                }
            });
            return result;
        });
    }

    private static String replay(CommandReceipt receipt, String type, String fingerprint) {
        if (!type.equals(receipt.commandType()) || !fingerprint.equals(receipt.payloadFingerprint())) {
            throw new WorkspaceFailure(ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT, "Command ID already belongs to another action");
        }
        return receipt.resultJson();
    }

    private Map<String, Object> cancel(StockRequestRecord row, ProductStock product) {
        UUID target = row.requestId();
        if (!"ACCEPTED".equals(row.status())) {
            throw new WorkspaceFailure(ProtocolInfo.ERROR_INVALID_TRANSITION, "Only accepted requests can be cancelled");
        }
        stock.updateBalances(row.sku(), product.onHand(), Math.subtractExact(product.reserved(), row.quantity()));
        requests.updateStatus(target, "CANCELLED");
        return Map.of("stockRequestId", target.toString(), "status", "CANCELLED");
    }

    private Map<String, Object> receive(UUID id, Actor actor, ProductStock product, int quantity, String note) {
        String sku = product.sku();
        if (quantity > Integer.MAX_VALUE - product.onHand()) {
            throw new WorkspaceFailure(ProtocolInfo.ERROR_VALIDATION, "Resulting stock would exceed the supported range");
        }
        int onHand = product.onHand() + quantity;
        stock.updateBalances(sku, onHand, product.reserved());
        jdbc.update("""
                INSERT INTO stock_movements (receiving_command_id, sku, quantity_delta, reason, recorded_by, note)
                VALUES (?, ?, ?, 'RECEIVE', ?, ?)
                """, id, sku, quantity, actor.name(), note);
        return Map.of("sku", sku, "quantity", quantity, "onHand", onHand, "reserved", product.reserved(),
                "available", onHand - product.reserved(), "status", "RECEIVED");
    }

    private static int size(JsonNode args) {
        if (!args.has("limit")) return 16;
        if (!args.path("limit").isInt() || args.path("limit").asInt() < 1 || args.path("limit").asInt() > 24) {
            throw new IllegalArgumentException("Invalid page size");
        }
        return args.path("limit").asInt();
    }

    private static String status(JsonNode args) {
        String status = field(args, "status", 12, true);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Invalid status filter");
        return status;
    }

    private static String search(JsonNode args) {
        String prefix = field(args, "search", 64, true);
        if (!prefix.isEmpty() && !ProtocolInfo.isValidSku(prefix)) throw new IllegalArgumentException("Invalid SKU prefix");
        return prefix;
    }

    private static String cursor(JsonNode args) {
        return field(args, "cursor", 512, true);
    }

    private static String sku(JsonNode args) {
        String sku = field(args, "sku", 64, false);
        if (!ProtocolInfo.isValidSku(sku)) throw new IllegalArgumentException("Invalid SKU");
        return sku;
    }

    private static int quantity(JsonNode args) {
        JsonNode quantity = args.path("quantity");
        if (!quantity.isInt() || quantity.asInt() <= 0) throw new IllegalArgumentException("Invalid quantity");
        return quantity.asInt();
    }

    private static UUID requestId(JsonNode args) {
        return uuid(field(args, "stockRequestId", 36, false));
    }

    private static UUID uuid(String value) {
        if (!TcpMessageCodec.isCanonicalUuidV4(value)) throw new IllegalArgumentException("Invalid UUID");
        return UUID.fromString(value);
    }

    private static String field(JsonNode args, String name, int length, boolean optional) {
        if (optional && !args.has(name)) return "";
        String value = args.path(name).textValue();
        if (value == null || value.length() > length || value.indexOf(0) >= 0) throw new IllegalArgumentException("Invalid field");
        return value;
    }

    private static void require(boolean permitted) {
        if (!permitted) throw new WorkspaceFailure(WorkspaceAccess.ERROR_FORBIDDEN, "Your role cannot perform this action");
    }

    private static WorkspaceFailure missingRequest() {
        return new WorkspaceFailure(ProtocolInfo.ERROR_NOT_FOUND, "Stock request was not found");
    }

    private static String accepted(UUID id, Object data) {
        return TcpMessageCodec.formatResultResponse(VERSION, id.toString(), ProtocolInfo.STATUS_ACCEPTED, data);
    }

    private static String error(String id, String code, String message) {
        return TcpMessageCodec.formatErrorResponse(VERSION, id, code, message);
    }

    private static String upgrade(String response) throws JsonProcessingException {
        ObjectNode frame = (ObjectNode) TcpMessageCodec.getObjectMapper().readTree(response);
        frame.put("v", VERSION);
        return frame.toString();
    }

    private static final class WorkspaceFailure extends RuntimeException {
        private final String code;

        private WorkspaceFailure(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
