package lk.thogamura.protocol.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpFrameCodecTest {

    @Test
    @DisplayName("Encodes and decodes a canonical JSON frame with newline delimiter")
    void shouldEncodeAndDecodeSingleFrame() throws IOException {
        String requestId = UUID.randomUUID().toString();
        String requestJson = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TcpFrameWriter writer = new TcpFrameWriter(out)) {
            writer.writeFrame(requestJson);
        }

        byte[] wireBytes = out.toByteArray();
        assertThat(wireBytes).endsWith((byte) '\n');

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(wireBytes))) {
            String decoded = reader.readNextFrameString();
            assertThat(decoded).isEqualTo(requestJson);

            // Clean EOF on next read
            assertThat(reader.readNextFrameString()).isNull();
        }
    }

    @Test
    @DisplayName("Handles frame split across multiple small stream reads")
    void shouldHandleFrameSplitAcrossMultipleReads() throws IOException {
        String requestId = UUID.randomUUID().toString();
        String json = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_GET_PRODUCTS, requestId, null);
        byte[] wireBytes = (json + "\n").getBytes(StandardCharsets.UTF_8);

        // Small 7-byte read buffer simulates fragmented TCP packets
        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(wireBytes), ProtocolInfo.MAX_FRAME_BYTES, 7)) {
            String decoded = reader.readNextFrameString();
            assertThat(decoded).isEqualTo(json);
            assertThat(reader.readNextFrameString()).isNull();
        }
    }

    @Test
    @DisplayName("Decodes multiple frames received together in a single read buffer")
    void shouldDecodeMultipleFramesInSingleRead() throws IOException {
        String req1 = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, UUID.randomUUID().toString(), null);
        String req2 = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_GET_PRODUCTS, UUID.randomUUID().toString(), null);
        byte[] combinedWireBytes = (req1 + "\n" + req2 + "\n").getBytes(StandardCharsets.UTF_8);

        // Generous buffer so both frames arrive in the first read
        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(combinedWireBytes), ProtocolInfo.MAX_FRAME_BYTES, 8192)) {
            String decoded1 = reader.readNextFrameString();
            String decoded2 = reader.readNextFrameString();

            assertThat(decoded1).isEqualTo(req1);
            assertThat(decoded2).isEqualTo(req2);
            assertThat(reader.readNextFrameString()).isNull();
        }
    }

    @Test
    @DisplayName("Accepts and strips optional carriage return (\\r\\n) preceding newline")
    void shouldStripCarriageReturn() throws IOException {
        String req = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, UUID.randomUUID().toString(), null);
        byte[] wireBytes = (req + "\r\n").getBytes(StandardCharsets.UTF_8);

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(wireBytes))) {
            String decoded = reader.readNextFrameString();
            assertThat(decoded).isEqualTo(req);
            assertThat(decoded).doesNotContain("\r");
        }
    }

    @Test
    @DisplayName("Rejects frame exceeding maximum 65,536 bytes and resets accumulator")
    void shouldRejectOversizedFrame() throws Exception {
        int oversizedLength = 65_537;
        byte[] oversizedData = new byte[oversizedLength + 1];
        for (int i = 0; i < oversizedLength; i++) {
            oversizedData[i] = 'A';
        }
        oversizedData[oversizedLength] = '\n';

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(oversizedData))) {
            assertThatThrownBy(reader::readNextFrameString)
                    .isInstanceOf(FrameTooLargeException.class)
                    .hasMessageContaining("exceeds maximum allowed limit");
        }
    }

    @Test
    @DisplayName("Writer rejects outgoing frame exceeding maximum allowed limit")
    void writerShouldRejectOversizedPayload() throws Exception {
        byte[] oversized = new byte[ProtocolInfo.MAX_FRAME_BYTES]; // With '\n', total is MAX_FRAME_BYTES + 1
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TcpFrameWriter writer = new TcpFrameWriter(out)) {
            assertThatThrownBy(() -> writer.writeFrame(oversized))
                    .isInstanceOf(FrameTooLargeException.class);
        }
    }

    @Test
    @DisplayName("Writer rejects payload containing unescaped newline delimiter")
    void writerShouldRejectPayloadWithInternalNewline() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (TcpFrameWriter writer = new TcpFrameWriter(out)) {
            assertThatThrownBy(() -> writer.writeFrame("line1\nline2"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("internal newline delimiter");
        }
    }

    @Test
    @DisplayName("Throws TruncatedFrameException when stream reaches EOF mid-frame")
    void shouldThrowOnTruncatedFrame() throws Exception {
        byte[] incomplete = "{\"v\":1,\"type\":\"PING\"".getBytes(StandardCharsets.UTF_8); // Missing '\n'

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(incomplete))) {
            assertThatThrownBy(reader::readNextFrameString)
                    .isInstanceOf(TruncatedFrameException.class)
                    .hasMessageContaining("without a trailing newline delimiter");
        }
    }

    @Test
    @DisplayName("Rejects invalid UTF-8 byte sequences strictly")
    void shouldRejectInvalidUtf8() throws Exception {
        byte[] invalidUtf8 = new byte[]{(byte) 0xC3, (byte) 0x28, (byte) '\n'}; // Invalid UTF-8 sequence

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(invalidUtf8))) {
            assertThatThrownBy(reader::readNextFrameString)
                    .isInstanceOf(MalformedFrameException.class)
                    .hasMessageContaining("invalid UTF-8")
                    .satisfies(e -> {
                        MalformedFrameException mfe = (MalformedFrameException) e;
                        assertThat(mfe.getErrorCode()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
                    });
        }
    }

    @Test
    @DisplayName("Rejects malformed JSON syntax")
    void shouldRejectMalformedJson() throws IOException {
        String malformed = "{\"v\":1, \"type\": \"PING\", unquoted: broken}\n";

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8)))) {
            assertThatThrownBy(reader::readNextFrameJson)
                    .isInstanceOf(MalformedFrameException.class)
                    .hasMessageContaining("Malformed JSON")
                    .satisfies(e -> assertThat(((MalformedFrameException) e).getErrorCode())
                            .isEqualTo(ProtocolInfo.ERROR_VALIDATION));
        }
    }

    @Test
    @DisplayName("Rejects JSON whose root is not an object (e.g. array or primitive)")
    void shouldRejectNonObjectJsonRoot() throws IOException {
        String jsonArray = "[1, 2, 3]\n";

        try (TcpFrameReader reader = new TcpFrameReader(new ByteArrayInputStream(jsonArray.getBytes(StandardCharsets.UTF_8)))) {
            assertThatThrownBy(reader::readNextFrameJson)
                    .isInstanceOf(MalformedFrameException.class)
                    .hasMessageContaining("Frame root must be a JSON object");
        }
    }

    @Test
    @DisplayName("Validates Protocol v1 envelope: checks version, type, and UUID requestId")
    void shouldValidateProtocolV1EnvelopeSuccessfully() throws IOException {
        String requestId = UUID.randomUUID().toString();
        String validJson = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        JsonNode node = TcpMessageCodec.parseJsonObject(validJson);
        TcpMessageCodec.validateEnvelope(node);

        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.get("type").asText()).isEqualTo(ProtocolInfo.COMMAND_PING);
        assertThat(node.get("requestId").asText()).isEqualTo(requestId);
    }

    @Test
    @DisplayName("Rejects frame with unsupported protocol version")
    void shouldRejectUnsupportedProtocolVersion() throws IOException {
        String reqId = UUID.randomUUID().toString();
        String json = "{\"v\":2,\"type\":\"PING\",\"requestId\":\"" + reqId + "\"}\n";

        JsonNode node = TcpMessageCodec.parseJsonObject(json);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node))
                .isInstanceOf(ProtocolVersionException.class)
                .hasMessageContaining("Unsupported protocol version: 2")
                .satisfies(e -> {
                    ProtocolVersionException pve = (ProtocolVersionException) e;
                    assertThat(pve.getErrorCode()).isEqualTo(ProtocolInfo.ERROR_UNSUPPORTED_VERSION);
                    assertThat(pve.getUnsupportedVersion()).isEqualTo(2);
                    assertThat(pve.getRequestId()).isEqualTo(reqId);
                });
    }

    @Test
    @DisplayName("Rejects frame with missing or malformed UUID requestId")
    void shouldRejectMalformedRequestId() throws IOException {
        // Missing requestId
        String noReqId = "{\"v\":1,\"type\":\"PING\"}\n";
        JsonNode node1 = TcpMessageCodec.parseJsonObject(noReqId);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node1))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("Missing required field 'requestId'");

        // Malformed requestId (not a UUID)
        String badReqId = "{\"v\":1,\"type\":\"PING\",\"requestId\":\"invalid-non-uuid-123\"}\n";
        JsonNode node2 = TcpMessageCodec.parseJsonObject(badReqId);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node2))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("not a valid canonical UUID")
                .satisfies(e -> assertThat(((MalformedFrameException) e).getRequestId()).isEqualTo("invalid-non-uuid-123"));

        // UUID v1 (not version 4)
        String v1Uuid = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        String v1Req = "{\"v\":1,\"type\":\"PING\",\"requestId\":\"" + v1Uuid + "\"}\n";
        JsonNode node3 = TcpMessageCodec.parseJsonObject(v1Req);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node3))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("not a valid canonical UUID v4")
                .satisfies(e -> assertThat(((MalformedFrameException) e).getRequestId()).isEqualTo(v1Uuid));

        // Uppercase UUID v4 (not canonical lowercase)
        String upperUuid = "A53E07FD-158B-4D77-9B97-51844197ED9C";
        String upperReq = "{\"v\":1,\"type\":\"PING\",\"requestId\":\"" + upperUuid + "\"}\n";
        JsonNode node4 = TcpMessageCodec.parseJsonObject(upperReq);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node4))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("not a valid canonical UUID v4");

        // Whitespace padded UUID v4
        String paddedReq = "{\"v\":1,\"type\":\"PING\",\"requestId\":\" a53e07fd-158b-4d77-9b97-51844197ed9c \"}\n";
        JsonNode node5 = TcpMessageCodec.parseJsonObject(paddedReq);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node5))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("not a valid canonical UUID v4");

        // Nil UUID (version 0)
        String nilReq = "{\"v\":1,\"type\":\"PING\",\"requestId\":\"00000000-0000-0000-0000-000000000000\"}\n";
        JsonNode node6 = TcpMessageCodec.parseJsonObject(nilReq);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node6))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("not a valid canonical UUID v4");

        // Non-textual integer requestId
        String intReq = "{\"v\":1,\"type\":\"PING\",\"requestId\":12345}\n";
        JsonNode node7 = TcpMessageCodec.parseJsonObject(intReq);
        assertThatThrownBy(() -> TcpMessageCodec.validateEnvelope(node7))
                .isInstanceOf(MalformedFrameException.class)
                .hasMessageContaining("Missing required field 'requestId'");
    }

    @Test
    @DisplayName("Formats contract-compliant canonical error response")
    void shouldFormatCanonicalErrorResponse() throws IOException {
        String reqId = UUID.randomUUID().toString();
        String errorJson = TcpMessageCodec.formatErrorResponse(
                reqId,
                ProtocolInfo.ERROR_VALIDATION,
                "Malformed JSON or missing required field 'sku'"
        );

        JsonNode node = TcpMessageCodec.parseJsonObject(errorJson);
        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
        assertThat(node.get("requestId").asText()).isEqualTo(reqId);
        assertThat(node.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(node.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(node.get("error").get("message").asText()).contains("Malformed JSON");
    }
}
