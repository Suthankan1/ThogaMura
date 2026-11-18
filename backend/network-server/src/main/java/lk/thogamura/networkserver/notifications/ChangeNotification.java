package lk.thogamura.networkserver.notifications;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable value record representing a version-only notification frame for branch gateways.
 *
 * <p>Wire format is newline-delimited JSON:</p>
 * <pre>
 * {"v":1,"type":"CHANGED","version":42}\n
 * </pre>
 *
 * <p>Security note: Under no circumstances may this payload include SKUs, quantities,
 * operator identities, customer info or authentication tokens. It acts purely as a
 * cache invalidation hint.</p>
 */
public record ChangeNotification(int v, String type, long version) {
    public static final int PROTOCOL_VERSION = 1;
    public static final String TYPE_CHANGED = "CHANGED";
    private static final Set<String> ALLOWED_KEYS = Set.of("v", "type", "version");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public ChangeNotification {
        if (v != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + v);
        }
        if (!TYPE_CHANGED.equals(type)) {
            throw new IllegalArgumentException("Unsupported notification type: " + type);
        }
        if (version < 0) {
            throw new IllegalArgumentException("Version must be non-negative: " + version);
        }
    }

    public static ChangeNotification of(long version) {
        return new ChangeNotification(PROTOCOL_VERSION, TYPE_CHANGED, version);
    }

    /**
     * Serializes this notification into newline-delimited UTF-8 JSON.
     *
     * @return the framed JSON string ending with '\n'
     */
    public String toWireJson() {
        return "{\"v\":" + v + ",\"type\":\"" + type + "\",\"version\":" + version + "}\n";
    }

    /**
     * Encodes this notification as UTF-8 bytes for socket transmission.
     *
     * @return byte array of the framed JSON
     */
    public byte[] toWireBytes() {
        return toWireJson().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Parses a newline-delimited JSON line into a validated {@link ChangeNotification}.
     * Fails fast if any unauthorized or unexpected fields are present.
     *
     * @param line raw JSON string
     * @return validated notification
     * @throws IllegalArgumentException if the format is invalid or extra fields exist
     */
    public static ChangeNotification parse(String line) {
        Objects.requireNonNull(line, "line cannot be null");
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Cannot parse empty line");
        }
        try {
            Map<String, Object> map = MAPPER.readValue(trimmed, new TypeReference<>() {});
            if (!ALLOWED_KEYS.equals(map.keySet())) {
                throw new IllegalArgumentException("Invalid fields in notification. Allowed only "
                        + ALLOWED_KEYS + ", got: " + map.keySet());
            }
            int v = ((Number) map.get("v")).intValue();
            String type = (String) map.get("type");
            long version = ((Number) map.get("version")).longValue();
            return new ChangeNotification(v, type, version);
        } catch (IOException | ClassCastException | NullPointerException e) {
            throw new IllegalArgumentException("Malformed notification frame: " + trimmed, e);
        }
    }
}
