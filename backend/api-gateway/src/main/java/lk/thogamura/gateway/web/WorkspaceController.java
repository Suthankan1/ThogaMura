package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lk.thogamura.gateway.network.security.TlsSocketFactory;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.access.WorkspaceAccess;
import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2")
@ConditionalOnProperty(name = "thogamura.access.enabled", havingValue = "true")
final class WorkspaceController {
    private static final String SESSION_KEY = WorkspaceController.class.getName();
    private static final String CSRF = "X-Workspace-CSRF";
    private final WorkspaceAccounts accounts;
    private final TcpCommandClient commands;
    private final WorkspaceAccess access;

    WorkspaceController(WorkspaceAccounts accounts, TcpCommandClient commands,
                        @Value("${thogamura.access.signing-key}") String signingKey) {
        if (!(commands.getSocketFactory() instanceof TlsSocketFactory)) {
            throw new IllegalStateException("Workspace requires hostname-validated warehouse TLS");
        }
        this.accounts = accounts;
        this.commands = commands;
        access = new WorkspaceAccess(signingKey);
    }

    record Session(Actor actor, String csrf, Instant expiresAt) {
        Map<String, String> publicDetails() {
            return Map.of("name", actor.name(), "role", actor.role().name(), "branch", actor.branch(), "csrf", csrf);
        }
    }

    @GetMapping("/session")
    ResponseEntity<?> session(HttpServletRequest request) {
        Session session = activeSession(request);
        return session == null ? signInRequired() : ResponseEntity.ok(session.publicDetails());
    }

    @PostMapping(value = "/session", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> signIn(@RequestBody JsonNode body, HttpServletRequest request) {
        if (!"browser".equals(request.getHeader("X-Workspace-Client"))) {
            return error(HttpStatus.FORBIDDEN, WorkspaceAccess.ERROR_FORBIDDEN, "Workspace sign-in must use the application");
        }
        Actor actor = accounts.authenticate(body.path("name").textValue(), body.path("password").textValue());
        if (actor == null) {
            return error(HttpStatus.UNAUTHORIZED, "SIGN_IN_FAILED", "Sign-in failed. Check the credentials or try again in a minute.");
        }
        HttpSession stored = request.getSession(true);
        request.changeSessionId();
        stored.setMaxInactiveInterval(30 * 60);
        Session session = new Session(actor, UUID.randomUUID().toString(), Instant.now().plusSeconds(8 * 3600));
        stored.setAttribute(SESSION_KEY, session);
        return ResponseEntity.ok(session.publicDetails());
    }

    @DeleteMapping("/session")
    ResponseEntity<?> signOut(HttpServletRequest request) {
        Session session = activeSession(request);
        if (session == null) return ResponseEntity.noContent().build();
        if (!validCsrf(request, session)) return csrfFailed();
        request.getSession(false).invalidate();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/products")
    ResponseEntity<?> products(HttpServletRequest request, @RequestParam(defaultValue = "16") int limit,
                               @RequestParam(defaultValue = "") String cursor, @RequestParam(defaultValue = "") String search)
            throws CommandTransportException {
        return read(request, ProtocolInfo.COMMAND_GET_PRODUCTS, Map.of("limit", limit, "cursor", cursor, "search", search));
    }

    @GetMapping("/requests")
    ResponseEntity<?> requests(HttpServletRequest request, @RequestParam(defaultValue = "16") int limit,
                               @RequestParam(defaultValue = "") String cursor, @RequestParam(defaultValue = "") String status)
            throws CommandTransportException {
        return read(request, ProtocolInfo.COMMAND_GET_REQUESTS, Map.of("limit", limit, "cursor", cursor, "status", status));
    }

    @GetMapping("/requests/{id}")
    ResponseEntity<?> request(HttpServletRequest request, @PathVariable String id) throws CommandTransportException {
        return read(request, WorkspaceAccess.GET_REQUEST, Map.of("stockRequestId", id));
    }

    @GetMapping("/movements")
    ResponseEntity<?> movements(HttpServletRequest request, @RequestParam(defaultValue = "16") int limit,
                                @RequestParam(defaultValue = "") String cursor) throws CommandTransportException {
        return read(request, ProtocolInfo.COMMAND_GET_MOVEMENTS, Map.of("limit", limit, "cursor", cursor));
    }

    @GetMapping("/suppliers/availability")
    ResponseEntity<?> supplier(HttpServletRequest request, @RequestParam String sku) throws CommandTransportException {
        return read(request, ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK, Map.of("sku", sku));
    }

    @PostMapping(value = "/requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> reserve(HttpServletRequest request, @RequestBody JsonNode body) throws CommandTransportException {
        return write(request, ProtocolInfo.COMMAND_REQUEST_STOCK, body, HttpStatus.CREATED);
    }

    @PostMapping(value = "/receiving", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> receive(HttpServletRequest request, @RequestBody JsonNode body) throws CommandTransportException {
        return write(request, WorkspaceAccess.RECEIVE, body, HttpStatus.CREATED);
    }

    @PostMapping("/requests/{id}/{action:cancel|pick|dispatch}")
    ResponseEntity<?> transition(HttpServletRequest request, @PathVariable String id, @PathVariable String action)
            throws CommandTransportException {
        String type = switch (action) {
            case "cancel" -> WorkspaceAccess.CANCEL;
            case "pick" -> ProtocolInfo.COMMAND_MARK_PICKED;
            case "dispatch" -> ProtocolInfo.COMMAND_MARK_DISPATCHED;
            default -> throw new IllegalArgumentException("Invalid transition");
        };
        return write(request, type, Map.of("stockRequestId", id), HttpStatus.OK);
    }

    private ResponseEntity<?> read(HttpServletRequest request, String command, Map<String, Object> args) throws CommandTransportException {
        Session session = activeSession(request);
        return session == null ? signInRequired() : send(session, command, UUID.randomUUID().toString(), args, HttpStatus.OK);
    }

    private ResponseEntity<?> write(HttpServletRequest request, String command, Object args, HttpStatus success)
            throws CommandTransportException {
        Session session = activeSession(request);
        if (session == null) return signInRequired();
        if (!validCsrf(request, session)) return csrfFailed();
        String key = request.getHeader("Idempotency-Key");
        if (!TcpMessageCodec.isCanonicalUuidV4(key)) {
            return error(HttpStatus.BAD_REQUEST, ProtocolInfo.ERROR_VALIDATION, "Idempotency-Key must be a lowercase UUID v4");
        }
        return send(session, command, key, args, success);
    }

    private ResponseEntity<?> send(Session session, String command, String id, Object args, HttpStatus success)
            throws CommandTransportException {
        JsonNode result = commands.sendCommand(WorkspaceAccess.VERSION, command, id, access.sign(session.actor(), command, id, args));
        String status = result.path("status").asText();
        JsonNode data = result.path("data");
        boolean supplier = ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK.equals(command);
        String expected = switch (command) {
            case ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK -> ProtocolInfo.STATUS_AVAILABLE;
            case ProtocolInfo.COMMAND_MARK_PICKED -> ProtocolInfo.STATUS_PICKED;
            case ProtocolInfo.COMMAND_MARK_DISPATCHED -> ProtocolInfo.STATUS_DISPATCHED;
            default -> ProtocolInfo.STATUS_ACCEPTED;
        };
        if (expected.equals(status)) {
            return data.isObject() ? ResponseEntity.status(success).body(data) : WarehouseHttpErrors.invalidData();
        }
        if (!ProtocolInfo.STATUS_ERROR.equals(status) && !ProtocolInfo.STATUS_REJECTED.equals(status) &&
                !(supplier && ProtocolInfo.STATUS_UNKNOWN.equals(status))) return WarehouseHttpErrors.invalidData();
        JsonNode failure = result.has("error") ? result.path("error") : data;
        String code = failure.path("code").asText(ProtocolInfo.ERROR_INTERNAL);
        HttpStatus http = switch (code) {
            case WorkspaceAccess.ERROR_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case ProtocolInfo.ERROR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ProtocolInfo.ERROR_VALIDATION -> HttpStatus.BAD_REQUEST;
            case ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT, ProtocolInfo.ERROR_INVALID_TRANSITION,
                    ProtocolInfo.ERROR_INSUFFICIENT_STOCK -> HttpStatus.CONFLICT;
            case ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case ProtocolInfo.ERROR_SUPPLIER_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case ProtocolInfo.ERROR_SUPPLIER_UNKNOWN -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", failure.path("message").asText("Warehouse could not confirm the command outcome."));
        body.put("requestId", id);
        return ResponseEntity.status(http).body(body);
    }

    private static Session activeSession(HttpServletRequest request) {
        HttpSession stored = request.getSession(false);
        if (stored == null || !(stored.getAttribute(SESSION_KEY) instanceof Session session)) return null;
        if (session.expiresAt().isAfter(Instant.now())) return session;
        stored.invalidate();
        return null;
    }

    private static boolean validCsrf(HttpServletRequest request, Session session) {
        String supplied = request.getHeader(CSRF);
        return supplied != null && supplied.length() == session.csrf().length() &&
                MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8), session.csrf().getBytes(StandardCharsets.UTF_8));
    }

    private static ResponseEntity<?> signInRequired() {
        return error(HttpStatus.UNAUTHORIZED, "SIGN_IN_REQUIRED", "Sign in to the branch workspace.");
    }

    private static ResponseEntity<?> csrfFailed() {
        return error(HttpStatus.FORBIDDEN, "INVALID_CSRF", "Reload the workspace and try again.");
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message));
    }
}
