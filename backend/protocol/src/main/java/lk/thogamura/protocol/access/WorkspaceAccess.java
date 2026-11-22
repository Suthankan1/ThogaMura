package lk.thogamura.protocol.access;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.tcp.TcpMessageCodec;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/** Authenticates short-lived gateway assertions inside the TLS-protected v2 command. */
public final class WorkspaceAccess {
    public static final int VERSION = 2;
    public static final String ERROR_FORBIDDEN = "FORBIDDEN";
    public static final String CANCEL = "CANCEL_REQUEST";
    public static final String RECEIVE = "RECEIVE_STOCK";
    public static final String GET_REQUEST = "GET_REQUEST";
    private static final int ASSERTION_SECONDS = 30;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    public enum Role { OPERATOR, PICKER, SUPERVISOR }

    public record Actor(String name, Role role, String branch) {
        public Actor {
            if (!isIdentifier(name) || role == null || !isIdentifier(branch)) {
                throw new IllegalArgumentException("Invalid workspace access identity");
            }
        }

        public boolean staff() {
            return role == Role.PICKER || role == Role.SUPERVISOR;
        }
    }

    private final SecretKeySpec secret;

    public WorkspaceAccess(String configured) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Workspace access key must be Base64");
        }
        if (bytes.length < 32) throw new IllegalArgumentException("Workspace access key requires at least 32 bytes");
        this.secret = new SecretKeySpec(bytes, "HmacSHA256");
    }

    public static boolean isIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    public Map<String, Object> sign(Actor actor, String command, String requestId, Object arguments) {
        JsonNode args = TcpMessageCodec.getObjectMapper().valueToTree(arguments);
        long expiry = Instant.now().getEpochSecond() + ASSERTION_SECONDS;
        return Map.of("args", args, "access", Map.of(
                "name", actor.name(), "role", actor.role().name(), "branch", actor.branch(), "expires", expiry,
                "signature", Base64.getUrlEncoder().withoutPadding().encodeToString(mac(actor, command, requestId, args, expiry))
        ));
    }

    public Actor verify(String command, String requestId, JsonNode data) {
        try {
            JsonNode claim = data.path("access");
            JsonNode args = data.path("args");
            if (!args.isObject() || !claim.path("expires").isIntegralNumber()) return null;
            Actor actor = new Actor(claim.path("name").textValue(), Role.valueOf(claim.path("role").asText()),
                    claim.path("branch").textValue());
            long expiry = claim.path("expires").longValue();
            long now = Instant.now().getEpochSecond();
            if (expiry < now || expiry > now + ASSERTION_SECONDS + 5) return null;

            byte[] given = Base64.getUrlDecoder().decode(claim.path("signature").asText());
            return MessageDigest.isEqual(given, mac(actor, command, requestId, args, expiry)) ? actor : null;
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private byte[] mac(Actor actor, String command, String requestId, JsonNode args, long expiry) {
        String message = String.join("\n", "THOGAMURA_ACCESS_V2", command, requestId, actor.name(),
                actor.role().name(), actor.branch(), Long.toString(expiry), args.toString());
        try {
            Mac hmac = Mac.getInstance(secret.getAlgorithm());
            hmac.init(secret);
            return hmac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Workspace access signature could not be computed", error);
        }
    }
}
