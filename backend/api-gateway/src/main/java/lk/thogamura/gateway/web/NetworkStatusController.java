package lk.thogamura.gateway.web;

import lk.thogamura.gateway.network.notifications.NotificationSubscriberLifecycle;
import lk.thogamura.gateway.network.notifications.SubscriberStatus;
import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

    public NetworkStatusController(
            @Value("${thogamura.notifications.subscriber.host:127.0.0.1}") String centralHost,
            @Value("${thogamura.notifications.subscriber.port:7444}") int nioPort,
            @Value("${thogamura.notifications.subscriber.enabled:false}") boolean subscriberEnabled,
            @Autowired(required = false) NotificationSubscriberLifecycle subscriberLifecycle,
            SseNotificationBroadcaster sseBroadcaster) {
        this.centralHost = centralHost;
        this.nioPort = nioPort;
        this.subscriberEnabled = subscriberEnabled;
        this.subscriberLifecycle = Optional.ofNullable(subscriberLifecycle);
        this.sseBroadcaster = sseBroadcaster;
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
}
