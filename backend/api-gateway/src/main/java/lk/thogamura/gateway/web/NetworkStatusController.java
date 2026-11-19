package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.gateway.network.notifications.NotificationSubscriberLifecycle;
import lk.thogamura.gateway.network.notifications.SubscriberStatus;
import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
@CrossOrigin(origins = "*")
public class NetworkStatusController {

    private final String centralHost;
    private final int nioPort;
    private final boolean subscriberEnabled;
    private final Optional<NotificationSubscriberLifecycle> subscriberLifecycle;
    private final SseNotificationBroadcaster sseBroadcaster;
    private final Optional<TcpCommandClient> tcpCommandClient;

    public NetworkStatusController(
            @Value("${thogamura.notifications.subscriber.host:127.0.0.1}") String centralHost,
            @Value("${thogamura.notifications.subscriber.port:7444}") int nioPort,
            @Value("${thogamura.notifications.subscriber.enabled:false}") boolean subscriberEnabled,
            @Autowired(required = false) NotificationSubscriberLifecycle subscriberLifecycle,
            SseNotificationBroadcaster sseBroadcaster,
            @Autowired(required = false) TcpCommandClient tcpCommandClient) {
        this.centralHost = centralHost;
        this.nioPort = nioPort;
        this.subscriberEnabled = subscriberEnabled;
        this.subscriberLifecycle = Optional.ofNullable(subscriberLifecycle);
        this.sseBroadcaster = sseBroadcaster;
        this.tcpCommandClient = Optional.ofNullable(tcpCommandClient);
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
            nioFeed.put("status", subStatus.name());
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
        tlsCommand.put("status", "NOT_IMPLEMENTED");
        tlsCommand.put("owner", "Member 5");
        status.put("tlsCommand", tlsCommand);

        return status;
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
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "TIMEOUT");
            body.put("code", "WAREHOUSE_TIMEOUT");
            body.put("message", "Warehouse command timed out: " + e.getMessage());
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
        } catch (ServerUnavailableException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "UNAVAILABLE");
            body.put("code", "WAREHOUSE_UNAVAILABLE");
            body.put("message", "Warehouse command server is unreachable: " + e.getMessage());
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (CommandCorrelationException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ERROR");
            body.put("code", "CORRELATION_ERROR");
            body.put("message", "Mismatched requestId received from warehouse: " + e.getMessage());
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
        } catch (CommandTransportException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ERROR");
            body.put("code", "INTERNAL_TRANSPORT_ERROR");
            body.put("message", "Warehouse command transport error: " + e.getMessage());
            body.put("warehouseHost", host);
            body.put("warehousePort", port);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
    }
}
