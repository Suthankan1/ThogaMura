package lk.thogamura.protocol.tcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.protocol.ProtocolInfo;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Message validation, strict UTF-8 decoding, and canonical JSON envelope formatting.
 *
 * <p>Enforces Protocol v1 rules defined in {@code docs/contracts/NETWORK_PROTOCOL_V1.md}:
 * <ul>
 *   <li>Strict UTF-8 byte stream validation (rejecting malformed sequences without replacement characters)</li>
 *   <li>Valid JSON root object enforcement</li>
 *   <li>Mandatory protocol version checking ({@code v == 1})</li>
 *   <li>UUID-formatted {@code requestId} correlation validation</li>
 *   <li>Canonical error and result envelope construction</li>
 * </ul>
 */
public final class TcpMessageCodec {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    private TcpMessageCodec() {
    }

    public static ObjectMapper getObjectMapper() {
        return OBJECT_MAPPER;
    }

    /**
     * Decodes raw bytes strictly as UTF-8, throwing {@link MalformedFrameException} if any byte
     * sequence is invalid, truncated, or unmappable.
     *
     * @param bytes raw payload bytes
     * @return decoded string
     * @throws MalformedFrameException if invalid UTF-8 byte sequences are detected
     */
    public static String decodeStrictUtf8(byte[] bytes) throws MalformedFrameException {
        Objects.requireNonNull(bytes, "bytes must not be null");
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer charBuffer = decoder.decode(ByteBuffer.wrap(bytes));
            return charBuffer.toString();
        } catch (CharacterCodingException e) {
            throw new MalformedFrameException(
                    "Frame contains invalid UTF-8 byte sequence: " + e.getMessage(),
                    ProtocolInfo.ERROR_VALIDATION,
                    null,
                    e
            );
        }
    }

    /**
     * Parses a JSON string into a {@link JsonNode}, strictly verifying that the root is a JSON Object.
     *
     * @param json JSON string
     * @return parsed root {@link JsonNode} object
     * @throws MalformedFrameException on syntax errors or non-object roots
     */
    public static JsonNode parseJsonObject(String json) throws MalformedFrameException {
        Objects.requireNonNull(json, "json must not be null");
        if (json.isBlank()) {
            throw new MalformedFrameException("Empty JSON frame payload", ProtocolInfo.ERROR_VALIDATION);
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(json);
            if (node == null || !node.isObject()) {
                throw new MalformedFrameException(
                        "Frame root must be a JSON object, but was: " + (node == null ? "null" : node.getNodeType()),
                        ProtocolInfo.ERROR_VALIDATION
                );
            }
            return node;
        } catch (JsonProcessingException e) {
            throw new MalformedFrameException(
                    "Malformed JSON frame syntax: " + e.getOriginalMessage(),
                    ProtocolInfo.ERROR_VALIDATION,
                    null,
                    e
            );
        }
    }

    /**
     * Validates that an incoming message complies with the Protocol v1 envelope specification:
     * <ul>
     *   <li>{@code v} must be an integer equal to {@link ProtocolInfo#WIRE_VERSION} (1)</li>
     *   <li>{@code type} must be a non-blank string</li>
     *   <li>{@code requestId} must be a valid canonical UUID</li>
     * </ul>
     *
     * @param node parsed JSON root object
     * @throws ProtocolVersionException if {@code v} is unsupported
     * @throws MalformedFrameException if required fields are missing or invalid
     */
    public static void validateEnvelope(JsonNode node) throws MalformedFrameException {
        validateEnvelope(node, ProtocolInfo.WIRE_VERSION);
    }

    public static void validateEnvelope(JsonNode node, int expectedVersion) throws MalformedFrameException {
        Objects.requireNonNull(node, "node must not be null");

        // Extract requestId early if possible for error correlation
        String potentialRequestId = extractRequestIdSilently(node);

        // Validate version 'v'
        if (!node.has("v") || !node.get("v").isInt()) {
            throw new MalformedFrameException(
                    "Missing or non-integer protocol version 'v'",
                    ProtocolInfo.ERROR_VALIDATION,
                    potentialRequestId
            );
        }
        int version = node.get("v").asInt();
        if (version != expectedVersion) {
            throw new ProtocolVersionException(
                    "Unsupported protocol version: " + version + " (expected " + expectedVersion + ")",
                    version,
                    potentialRequestId
            );
        }

        // Validate command/message type
        if (!node.hasNonNull("type") || !node.get("type").isTextual() || node.get("type").asText().isBlank()) {
            throw new MalformedFrameException(
                    "Missing or empty required field 'type'",
                    ProtocolInfo.ERROR_VALIDATION,
                    potentialRequestId
            );
        }

        // Validate requestId
        if (!node.hasNonNull("requestId") || !node.get("requestId").isTextual()) {
            throw new MalformedFrameException(
                    "Missing required field 'requestId'",
                    ProtocolInfo.ERROR_VALIDATION,
                    null
            );
        }
        String rawRequestIdStr = node.get("requestId").asText();
        if (!isCanonicalUuidV4(rawRequestIdStr)) {
            throw new MalformedFrameException(
                    "Field 'requestId' is not a valid canonical UUID v4: '" + rawRequestIdStr + "'",
                    ProtocolInfo.ERROR_VALIDATION,
                    rawRequestIdStr
            );
        }
    }

    /**
     * Verifies that the string is a valid canonical lowercase UUID v4 (36 characters).
     *
     * @param str string to check
     * @return true if valid canonical lowercase UUID v4, false otherwise
     */
    public static boolean isCanonicalUuidV4(String str) {
        if (str == null || str.length() != 36) {
            return false;
        }
        try {
            UUID parsed = UUID.fromString(str);
            return parsed.version() == 4 && parsed.toString().equals(str);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Constructs a contract-compliant Protocol v1 error response JSON string.
     *
     * @param requestId correlated request identifier (or null to generate a fallback UUID)
     * @param errorCode standard error code from {@link ProtocolInfo}
     * @param message descriptive error message (safe for clients, no internal stack traces)
     * @return serialized JSON error envelope string
     */
    public static String formatErrorResponse(String requestId, String errorCode, String message) {
        return formatErrorResponse(ProtocolInfo.WIRE_VERSION, requestId, errorCode, message);
    }

    public static String formatErrorResponse(int version, String requestId, String errorCode, String message) {
        String effectiveRequestId = (requestId != null && !requestId.isBlank())
                ? requestId
                : UUID.randomUUID().toString();

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("v", version);
        root.put("type", ProtocolInfo.TYPE_RESULT);
        root.put("requestId", effectiveRequestId);
        root.put("status", ProtocolInfo.STATUS_ERROR);

        ObjectNode err = root.putObject("error");
        err.put("code", errorCode != null ? errorCode : ProtocolInfo.ERROR_INTERNAL);
        err.put("message", message != null ? message : "An unexpected server error occurred");

        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize canonical error response", e);
        }
    }

    /**
     * Constructs a contract-compliant Protocol v1 result response JSON string.
     *
     * @param requestId correlated request identifier
     * @param status status string (e.g. {@code ACCEPTED}, {@code PONG}, {@code PICKED}, etc.)
     * @param data result payload object (or null)
     * @return serialized JSON result envelope string
     */
    public static String formatResultResponse(String requestId, String status, Object data) {
        return formatResultResponse(ProtocolInfo.WIRE_VERSION, requestId, status, data);
    }

    public static String formatResultResponse(int version, String requestId, String status, Object data) {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("v", version);
        root.put("type", ProtocolInfo.TYPE_RESULT);
        root.put("requestId", requestId);
        root.put("status", status);

        if (data != null) {
            root.set("data", OBJECT_MAPPER.valueToTree(data));
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize canonical result response", e);
        }
    }

    /**
     * Constructs a contract-compliant Protocol v1 command request JSON string.
     *
     * @param type command type from {@link ProtocolInfo}
     * @param requestId request UUID
     * @param data command data payload (or null)
     * @return serialized JSON command envelope string
     */
    public static String formatRequest(String type, String requestId, Object data) {
        return formatRequest(ProtocolInfo.WIRE_VERSION, type, requestId, data);
    }

    public static String formatRequest(int version, String type, String requestId, Object data) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(requestId, "requestId must not be null");

        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("v", version);
        root.put("type", type);
        root.put("requestId", requestId);

        if (data != null) {
            root.set("data", OBJECT_MAPPER.valueToTree(data));
        }

        try {
            return OBJECT_MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize canonical request envelope", e);
        }
    }

    private static String extractRequestIdSilently(JsonNode node) {
        if (node != null && node.hasNonNull("requestId") && node.get("requestId").isTextual()) {
            return node.get("requestId").asText().trim();
        }
        return null;
    }
}
