package lk.thogamura.networkserver.notifications;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration parameters and private LAN security controls for the non-blocking
 * Java NIO notification server.
 *
 * <p>Security Controls (Issue #33):
 * <ul>
 *   <li>Default binding is strictly loopback ({@code 127.0.0.1:7444}) to prevent accidental LAN/WAN exposure.</li>
 *   <li>Wildcard binding ({@code 0.0.0.0} or {@code ::}) is strictly forbidden for the unencrypted feed.</li>
 *   <li>Binding to a non-loopback interface (e.g., a private LAN IP) requires explicit operator approval
 *       via {@code allowNonLoopback=true}.</li>
 *   <li>Inbound connections can be filtered against an explicit allowlist of authorized gateway IP addresses.</li>
 * </ul>
 * </p>
 */
public record NotificationServerConfig(
        String host,
        int port,
        int maxClients,
        int maxPendingMessagesPerClient,
        int socketSendBufferSize,
        boolean allowNonLoopback,
        Set<String> allowedClientAddresses
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7444;
    public static final int DEFAULT_MAX_CLIENTS = 100;
    public static final int DEFAULT_MAX_PENDING_MESSAGES = 50;
    public static final int DEFAULT_SOCKET_SEND_BUFFER = 0; // 0 = OS default

    public NotificationServerConfig(String host, int port, int maxClients, int maxPendingMessagesPerClient, int socketSendBufferSize) {
        this(host, port, maxClients, maxPendingMessagesPerClient, socketSendBufferSize,
                !isLoopbackAddress(host),
                isLoopbackAddress(host) ? Set.of(DEFAULT_HOST) : Collections.emptySet());
    }

    public NotificationServerConfig(String host, int port, int maxClients, int maxPendingMessagesPerClient) {
        this(host, port, maxClients, maxPendingMessagesPerClient, DEFAULT_SOCKET_SEND_BUFFER);
    }

    public NotificationServerConfig {
        Objects.requireNonNull(host, "host cannot be null");
        String trimmedHost = host.trim();
        if (trimmedHost.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }

        // Prohibit wildcard addresses (0.0.0.0 / ::)
        if (isWildcardAddress(trimmedHost)) {
            throw new IllegalArgumentException(
                    "Wildcard binding (0.0.0.0 / ::) is prohibited for the unencrypted NIO notification feed. "
                            + "Specify an explicit loopback or private LAN IP address.");
        }

        // Require explicit operator approval before binding beyond loopback
        boolean loopback = isLoopbackAddress(trimmedHost);
        if (!loopback && !allowNonLoopback) {
            throw new IllegalArgumentException(
                    "Binding to non-loopback address '" + trimmedHost
                            + "' requires explicit operator approval (allowNonLoopback=true).");
        }

        if (!loopback && !isPrivateLiteral(trimmedHost)) {
            throw new IllegalArgumentException("NIO must bind to a private LAN IP address");
        }

        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535 (got " + port + ")");
        }
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive (got " + maxClients + ")");
        }
        if (maxPendingMessagesPerClient <= 0) {
            throw new IllegalArgumentException("maxPendingMessagesPerClient must be positive (got "
                    + maxPendingMessagesPerClient + ")");
        }
        if (socketSendBufferSize < 0) {
            throw new IllegalArgumentException("socketSendBufferSize cannot be negative (got "
                    + socketSendBufferSize + ")");
        }

        // Validate and sanitize allowed client addresses
        Set<String> sanitizedAllowed = new HashSet<>();
        if (allowedClientAddresses != null) {
            for (String addr : allowedClientAddresses) {
                if (addr != null && !addr.isBlank()) {
                    String clean = addr.trim();
                    if ("*".equals(clean) || "0.0.0.0".equals(clean) || "::".equals(clean)) {
                        throw new IllegalArgumentException(
                                "Wildcard entries ('*' or '0.0.0.0') are prohibited in allowed client addresses. "
                                        + "Specify explicit client IP addresses.");
                    }
                    if ("localhost".equalsIgnoreCase(clean)) {
                        sanitizedAllowed.add("localhost");
                    } else if (isPrivateLiteral(clean)) {
                        sanitizedAllowed.add(canonicalIp(clean));
                    } else {
                        throw new IllegalArgumentException("NIO clients must be listed as exact private or loopback IP addresses");
                    }
                }
            }
        }
        allowedClientAddresses = Collections.unmodifiableSet(sanitizedAllowed);
        host = "localhost".equalsIgnoreCase(trimmedHost) ? "127.0.0.1" : canonicalIp(trimmedHost);
    }

    private static boolean isPrivateLiteral(String value) {
        if (!value.matches("[0-9.]+") && !value.contains(":")) return false;
        try {
            InetAddress address = InetAddress.getByName(value);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalV6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return address.isLoopbackAddress() || (bytes.length == 4 && address.isSiteLocalAddress()) || uniqueLocalV6;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static String canonicalIp(String literal) {
        try {
            return InetAddress.getByName(literal).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid NIO client IP address", e);
        }
    }

    /**
     * Determines whether a host string represents a wildcard address (0.0.0.0 or ::).
     */
    public static boolean isWildcardAddress(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.trim();
        if ("0.0.0.0".equals(h) || "::".equals(h) || "0:0:0:0:0:0:0:0".equals(h)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(h);
            return addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Determines whether a host string represents a loopback address.
     */
    public static boolean isLoopbackAddress(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.trim();
        if ("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "::1".equals(h)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(h);
            return addr.isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Returns true if this server configuration binds to a loopback address.
     */
    public boolean isLoopback() {
        return isLoopbackAddress(host);
    }

    /**
     * Checks if an incoming client connection is permitted by the application-level allowlist.
     *
     * @param remoteAddress the remote socket address of the incoming connection
     * @return true if permitted, false if the connection should be rejected immediately
     */
    public boolean isClientAllowed(SocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return false;
        }
        if (!(remoteAddress instanceof InetSocketAddress inet)) {
            return false;
        }
        InetAddress address = inet.getAddress();
        if (address == null) {
            return false;
        }

        String clientIp = address.getHostAddress();
        // Normalize IPv6-mapped IPv4 addresses (::ffff:192.168.1.20 -> 192.168.1.20)
        if (clientIp.startsWith("::ffff:") && clientIp.contains(".")) {
            clientIp = clientIp.substring(7);
        }

        // If no explicit client allowlist is configured
        if (allowedClientAddresses.isEmpty()) {
            // By default, loopback server accepts loopback clients
            if (isLoopback()) {
                return address.isLoopbackAddress();
            }
            // If on private LAN with empty allowlist, require explicit configuration (fail closed)
            return false;
        }

        // Check if the client IP is explicitly in the allowlist
        if (allowedClientAddresses.contains(clientIp)) {
            return true;
        }

        // Also permit loopback if allowlist includes localhost or 127.0.0.1
        if (address.isLoopbackAddress() &&
                (allowedClientAddresses.contains("127.0.0.1") || allowedClientAddresses.contains("localhost") || allowedClientAddresses.contains("0:0:0:0:0:0:0:1"))) {
            return true;
        }

        return false;
    }

    /**
     * Creates default development configuration bound to 127.0.0.1:7444.
     */
    public static NotificationServerConfig defaultLocal() {
        return new NotificationServerConfig(
                DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES,
                DEFAULT_SOCKET_SEND_BUFFER, false, Set.of(DEFAULT_HOST));
    }

    /**
     * Creates a configuration with an explicit host and port.
     */
    public static NotificationServerConfig of(String host, int port) {
        boolean loopback = isLoopbackAddress(host);
        return new NotificationServerConfig(
                host, port, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES,
                DEFAULT_SOCKET_SEND_BUFFER, !loopback, loopback ? Set.of(DEFAULT_HOST) : Collections.emptySet());
    }

    /**
     * Creates a configuration on loopback with an explicit port (e.g., 0 for dynamic testing).
     */
    public static NotificationServerConfig ofPort(int port) {
        return new NotificationServerConfig(
                DEFAULT_HOST, port, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES,
                DEFAULT_SOCKET_SEND_BUFFER, false, Set.of(DEFAULT_HOST));
    }

    /**
     * Creates a secure private LAN configuration with explicit operator approval and client allowlist.
     *
     * @param host the private LAN IP of the warehouse machine
     * @param port the listening port (e.g., 7444)
     * @param allowedClients approved branch gateway IP addresses
     */
    public static NotificationServerConfig forPrivateLan(String host, int port, Set<String> allowedClients) {
        return new NotificationServerConfig(
                host, port, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES,
                DEFAULT_SOCKET_SEND_BUFFER, true, allowedClients);
    }
}
