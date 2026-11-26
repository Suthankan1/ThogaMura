package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.gateway.network.notifications.NotificationSubscriberLifecycle;
import lk.thogamura.gateway.network.notifications.SubscriberStatus;
import lk.thogamura.gateway.network.security.TlsClientProperties;
import lk.thogamura.gateway.network.security.TlsSocketFactory;
import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.net.SocketFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Observability endpoint exposing the connection status of the central warehouse
 * network services (Java NIO notification feed and SSE broadcaster).
 *
 * <p>Conforms to {@code docs/contracts/HTTP_API_V1.md}.
 * Enables the frontend to detect offline or paused states.</p>
 */
@RestController
@RequestMapping("/api/v1")
public class NetworkStatusController {

    private static final Logger logger = LoggerFactory.getLogger(NetworkStatusController.class);

    private final String centralHost;
    private final int nioPort;
    private final boolean subscriberEnabled;
    private final Optional<NotificationSubscriberLifecycle> subscriberLifecycle;
    private final SseNotificationBroadcaster sseBroadcaster;
    private final Optional<TcpCommandClient> tcpCommandClient;
    private final Optional<TlsClientProperties> tlsProperties;

    public NetworkStatusController(
            @Value("${thogamura.notifications.subscriber.host:127.0.0.1}") String centralHost,
            @Value("${thogamura.notifications.subscriber.port:7444}") int nioPort,
            @Value("${thogamura.notifications.subscriber.enabled:false}") boolean subscriberEnabled,
            @Autowired(required = false) NotificationSubscriberLifecycle subscriberLifecycle,
            SseNotificationBroadcaster sseBroadcaster,
            @Autowired(required = false) TcpCommandClient tcpCommandClient,
            @Autowired(required = false) TlsClientProperties tlsProperties) {
        this.centralHost = centralHost;
        this.nioPort = nioPort;
        this.subscriberEnabled = subscriberEnabled;
        this.subscriberLifecycle = Optional.ofNullable(subscriberLifecycle);
        this.sseBroadcaster = sseBroadcaster;
        this.tcpCommandClient = Optional.ofNullable(tcpCommandClient);
        this.tlsProperties = Optional.ofNullable(tlsProperties);
    }

    @GetMapping("/network-status")
    public Map<String, Object> getNetworkStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("centralHost", centralHost);
        status.put("nioPort", nioPort);

        Map<String, Object> nioFeed = new LinkedHashMap<>();
        nioFeed.put("enabled", subscriberEnabled);

        if (subscriberLifecycle.isPresent()) {
            NotificationSubscriberLifecycle lifecycle = subscriberLifecycle.get();
            SubscriberStatus subStatus = lifecycle.getStatus();
            nioFeed.put("status", subStatus != null ? subStatus.name() : SubscriberStatus.DISCONNECTED.name());
            nioFeed.put("connected", lifecycle.isConnected());
            if (lifecycle.getSubscriber() != null) {
                nioFeed.put("lastReceivedVersion", lifecycle.getSubscriber().getLastReceivedVersion());
                nioFeed.put("notificationCount", lifecycle.getSubscriber().getNotificationCount());
                nioFeed.put("reconnectCount", lifecycle.getSubscriber().getReconnectCount());
            } else {
                nioFeed.put("lastReceivedVersion", -1L);
                nioFeed.put("notificationCount", 0);
                nioFeed.put("reconnectCount", 0);
            }
        } else {
            nioFeed.put("status", SubscriberStatus.DISCONNECTED.name());
            nioFeed.put("connected", false);
            nioFeed.put("lastReceivedVersion", -1L);
            nioFeed.put("notificationCount", 0);
            nioFeed.put("reconnectCount", 0);
        }
        status.put("nioFeed", nioFeed);

        Map<String, Object> sse = new LinkedHashMap<>();
        sse.put("activeClients", sseBroadcaster.getActiveEmitterCount());
        sse.put("broadcastCount", sseBroadcaster.getBroadcastCount());
        sse.put("lastBroadcastVersion", sseBroadcaster.getLastBroadcastVersion());
        status.put("sse", sse);

        Map<String, Object> tlsCommand = new LinkedHashMap<>();
        ProbeOutcome tlsOutcome = determineTlsStatus();
        tlsCommand.put("status", tlsOutcome.status());
        tlsCommand.put("owner", "Member 5");
        if (tlsOutcome.serverTime() != null) {
            tlsCommand.put("serverTime", tlsOutcome.serverTime());
            tlsCommand.put("clockSkewMs", tlsOutcome.clockSkewMs());
        }
        status.put("tlsCommand", tlsCommand);

        return status;
    }

    private final Object probeLock = new Object();
    private ProbeResult latestProbe;

    private record ProbeOutcome(String status, String serverTime, Long clockSkewMs) {
        static ProbeOutcome of(String status) {
            return new ProbeOutcome(status, null, null);
        }
    }

    private record ProbeResult(Path truststore, ProbeOutcome outcome, long expiresNanos) {}

    private ProbeOutcome determineTlsStatus() {
        if (tlsProperties.isEmpty() || !tlsProperties.get().isEnabled()) {
            return ProbeOutcome.of("DISABLED");
        }

        TlsClientProperties props = tlsProperties.get();
        if (!props.isHostnameVerification()) {
            return ProbeOutcome.of("MISCONFIGURED");
        }

        String truststorePath = props.getTruststorePath();
        if (truststorePath == null || truststorePath.isBlank()) {
            return ProbeOutcome.of("MISCONFIGURED");
        }
        Path path = Path.of(truststorePath);
        if (!Files.exists(path) || !Files.isReadable(path)) {
            return ProbeOutcome.of("MISCONFIGURED");
        }

        if (tcpCommandClient.isEmpty()) {
            return ProbeOutcome.of("UNREACHABLE");
        }

        TcpCommandClient client = tcpCommandClient.get();
        SocketFactory sf = client.getSocketFactory();
        if (sf != null && !(sf instanceof TlsSocketFactory)) {
            return ProbeOutcome.of("MISCONFIGURED");
        }

        synchronized (probeLock) {
            if (latestProbe != null && latestProbe.truststore().equals(path) && latestProbe.expiresNanos() - System.nanoTime() > 0) {
                return latestProbe.outcome();
            }
            ProbeOutcome outcome;
            try {
                JsonNode pong = client.pingOnce();
                String serverTime = pong != null ? pong.path("data").path("serverTime").textValue() : null;
                Long clockSkewMs = computeClockSkewMs(serverTime);
                outcome = new ProbeOutcome("OPERATIONAL", serverTime, clockSkewMs);
            } catch (CommandTransportException e) {
                logger.debug("TLS diagnostic probe failed for {}", e.getRequestId());
                outcome = ProbeOutcome.of("UNREACHABLE");
            }
            latestProbe = new ProbeResult(path, outcome, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5));
            return outcome;
        }
    }

    private static Long computeClockSkewMs(String serverTime) {
        if (serverTime == null || serverTime.isBlank()) {
            return null;
        }
        try {
            long serverMs = java.time.Instant.parse(serverTime).toEpochMilli();
            return Math.abs(System.currentTimeMillis() - serverMs);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Executes a real Java TCP diagnostic PING command to the central warehouse server
     * via {@link TcpCommandClient}, returning the correlated PONG result.
     *
     * <p>Enforces Issue #9 and Member 1 diagnostic round-trip requirements.
     * Fails cleanly with HTTP 503 if the warehouse command server is stopped or unreachable.</p>
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> pingWarehouseCommandServer() {
        if (tcpCommandClient.isEmpty()) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UNAVAILABLE");
            body.put("code", "CLIENT_NOT_CONFIGURED");
            body.put("message", "TcpCommandClient is not configured on this gateway");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        TcpCommandClient client = tcpCommandClient.get();
        String host = client.getConfig().getHost();
        int port = client.getConfig().getPort();

        try {
            JsonNode response = client.ping();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "PONG");
            body.put("requestId", response.path("requestId").asText());
            body.put("serverTime", response.path("data").path("serverTime").asText());
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.ok(body);
        } catch (CommandTimeoutException e) {
            logger.warn("Warehouse command timed out during ping to {}:{}: {}", host, port, e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "TIMEOUT");
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out. Please retry.");
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (ServerUnavailableException e) {
            logger.warn("Warehouse command server is unreachable during ping to {}:{}: {}", host, port, e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UNAVAILABLE");
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable. Please verify service status.");
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandCorrelationException e) {
            logger.error("Command correlation error during ping: {}", e.getMessage());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ERROR");
            body.put("code", "CORRELATION_ERROR");
            body.put("message", "Invalid or mismatched response received from warehouse server.");
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        } catch (CommandTransportException e) {
            logger.error("Command transport error during ping to {}:{}: {}", host, port, e.getMessage(), e);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ERROR");
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error occurred.");
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
