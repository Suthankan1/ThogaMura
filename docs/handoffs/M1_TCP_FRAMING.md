# Member 1 Handoff — TCP Message Framing (Issue #6)

- **Owner:** Member 1 (@sankharavindu — Sankha Ravindu)
- **Role:** Java TCP Networking
- **Module:** `backend/protocol` (`lk.thogamura.protocol.tcp`)
- **Status:** Complete (Verified with unit and real-socket integration tests)
- **Reference Issue:** [#6 — [M1][W1] Implement bounded UTF-8 newline JSON TCP framing](https://github.com/Suthankan1/ThogaMura/issues/6)
- **Contract:** [NETWORK_PROTOCOL_V1.md](file:///Users/suthankan/Desktop/Projects/ThogaMura/docs/contracts/NETWORK_PROTOCOL_V1.md) (v1.0.0 Frozen)

---

## 1. Overview & Java APIs Used

TCP is an unstructured byte stream transport. A single socket `InputStream.read()` does not correspond to a single discrete message:
1. A single frame may be fragmented across multiple network reads.
2. Multiple distinct frames may coalesce and arrive together in a single read burst.
3. Frames must be bounded in length to prevent heap exhaustion.

To enforce these guarantees without using NIO (which is owned by Member 3), Member 1 implemented traditional Java Socket stream framing in `lk.thogamura.protocol.tcp`:

### Java Standard APIs Used:
- `java.net.Socket` and `java.net.ServerSocket`: Real stream-oriented TCP endpoints.
- `java.io.InputStream` and `java.io.OutputStream`: Byte-level stream reading and writing.
- `java.io.ByteArrayOutputStream`: Bounded in-memory frame accumulator.
- `java.nio.charset.CharsetDecoder` with `StandardCharsets.UTF_8`: Strict UTF-8 validation configured with `CodingErrorAction.REPORT` (rejecting invalid bytes without silent character replacement).
- `com.fasterxml.jackson.databind.ObjectMapper`: Validates JSON syntax and root object structure.
- `java.util.UUID`: Validates and parses `requestId` correlation tokens.

---

## 2. Frame Format & Boundary Specification

- **Delimiter:** Strictly newline-delimited (`\n`, ASCII `0x0A`).
- **Carriage Return:** Optional `\r` (ASCII `0x0D`) immediately preceding `\n` is accepted and stripped during decoding.
- **Character Encoding:** Strictly UTF-8.
- **Maximum Frame Bounds:** Strictly **65,536 bytes** (64 KB) per frame including the delimiter (`ProtocolInfo.MAX_FRAME_BYTES`).
- **Memory Bounding:** `TcpFrameReader` allocates a bounded buffer (8 KB read buffer, max 64 KB accumulator). If incoming data exceeds 65,536 bytes without a newline delimiter, the accumulator is immediately reset and `FrameTooLargeException` is thrown, protecting heap memory.

---

## 3. Architecture & Class Layout

```
backend/protocol/src/main/java/lk/thogamura/protocol/tcp/
├── FrameTooLargeException.java      # Thrown when frame exceeds 65,536 bytes
├── TruncatedFrameException.java     # Thrown on premature EOF mid-frame
├── MalformedFrameException.java     # Base error for invalid UTF-8/JSON/envelope
├── ProtocolVersionException.java    # Thrown when version 'v' is not 1
├── TcpFrameReader.java              # Bounded stream decoder over InputStream
├── TcpFrameWriter.java              # Delimited stream encoder over OutputStream
└── TcpMessageCodec.java             # Strict UTF-8, JSON envelope validation & error formatting
```

### Core Components:

1. **`TcpFrameReader`:**
   - Buffers chunks from `InputStream.read(buffer)`.
   - Accumulates slices until `\n` is encountered.
   - Preserves unconsumed residual bytes for subsequent frames when multiple frames arrive coalesced.
   - Throws `TruncatedFrameException` if the stream ends before a newline delimiter.
   - Returns `null` on clean EOF at frame boundary.

2. **`TcpFrameWriter`:**
   - Rejects payloads containing raw unescaped `\n` to prevent framing corruption.
   - Enforces outgoing frame bounds (`payload.length + 1 <= 65536`).
   - Appends `\n` and immediately flushes the output stream.

3. **`TcpMessageCodec`:**
   - Validates root JSON object structure.
   - Enforces `v == 1` (rejecting unsupported versions with `UNSUPPORTED_VERSION`).
   - Validates mandatory fields `type` and `requestId` (UUID v4 format).
   - Generates canonical error envelopes:
     ```json
     {
       "v": 1,
       "type": "RESULT",
       "requestId": "5a3e07fd-158b-4d77-9b97-51844197ed9c",
       "status": "ERROR",
       "error": {
         "code": "VALIDATION_ERROR",
         "message": "Malformed JSON frame syntax"
       }
     }
     ```

---

## 4. Test Verification & Actual Results

Automated unit tests and real-socket network integration tests were executed in `backend/protocol`:

```bash
mvn -f backend/protocol/pom.xml clean test
```

### Actual Results:
```text
-------------------------------------------------------
 T E S T S
-------------------------------------------------------
Running lk.thogamura.protocol.tcp.TcpFrameCodecTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0 -- in TcpFrameCodecTest
Running lk.thogamura.protocol.tcp.TcpSocketNetworkFramingTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in TcpSocketNetworkFramingTest

Results:
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Scenarios Covered:
1. `shouldEncodeAndDecodeSingleFrame`: Standard single-frame roundtrip.
2. `shouldHandleFrameSplitAcrossMultipleReads`: 7-byte buffer simulates fragmented TCP packets.
3. `shouldDecodeMultipleFramesInSingleRead`: Two frames in one buffer slice decode sequentially.
4. `shouldStripCarriageReturn`: Strips `\r` from `\r\n`.
5. `shouldRejectOversizedFrame`: Frame > 65,536 bytes throws `FrameTooLargeException`.
6. `writerShouldRejectOversizedPayload`: Writer enforces outgoing bounds.
7. `writerShouldRejectPayloadWithInternalNewline`: Rejects payload with unescaped newline.
8. `shouldThrowOnTruncatedFrame`: Unexpected EOF throws `TruncatedFrameException`.
9. `shouldRejectInvalidUtf8`: Rejects non-UTF-8 bytes (`0xC3 0x28`).
10. `shouldRejectMalformedJson`: Rejects syntax errors.
11. `shouldRejectNonObjectJsonRoot`: Rejects JSON array `[1, 2, 3]`.
12. `shouldValidateProtocolV1EnvelopeSuccessfully`: Validates `v: 1`, `type`, and UUID `requestId`.
13. `shouldRejectUnsupportedProtocolVersion`: `v: 2` throws `ProtocolVersionException` with code `UNSUPPORTED_VERSION`.
14. `shouldRejectMalformedRequestId`: Rejects non-UUID strings.
15. `shouldFormatCanonicalErrorResponse`: Validates error envelope serialization.
16. `shouldPerformDuplexRoundtripOverRealSocket`: Real loopback `127.0.0.1` client/server duplex communication.
17. `shouldReconstructFragmentedFrameOverRealSocket`: Physical 3-byte socket writes with `Thread.sleep` reconstruct into a single frame.
18. `shouldDecodeMultipleFramesArrivingInSingleTcpPacket`: Two frames in a single TCP packet decode independently.
19. `shouldHandleOversizedFrameOnSocketSafely`: 70,000-byte flood triggers `FrameTooLargeException` and returns error frame without crashing.
20. `shouldDetectPrematureSocketDisconnectAsTruncatedFrame`: Abrupt TCP client disconnect mid-frame throws `TruncatedFrameException`.
21. `shouldHandleCarriageReturnOverRealSocket`: Real network socket handles `\r\n` cleanly.

### Full Reactor Verification:
```bash
mvn -f backend/pom.xml clean verify
```
- `protocol`: 21 tests passed
- `network-server`: 36 tests passed
- `api-gateway`: 43 tests passed
- **Total: 100 backend tests passed.**

---

## 5. Limitations & Boundary Rules

1. **Not a Full Command Server:** This codec is the transport encoder/decoder. Dispatching business commands (`REQUEST_STOCK`, `MARK_PICKED`, etc.) to PostgreSQL belongs to Issue #7 and Member 4.
2. **Not TLS-Wrapped Yet:** Plain `Socket` and `ServerSocket` are used for framing verification. Wrapping in JSSE `SSLSocket` / `SSLServerSocket` belongs to Issue #5 / #23 with Member 5.
3. **No Java NIO:** Member 3 owns Java NIO on port 7444. Member 1 uses standard blocking I/O (`InputStream`/`OutputStream`) on port 7443.

---

## 6. How to Demonstrate

To demonstrate framing and socket behavior independently:

```bash
# Run the test suite displaying real socket transmission evidence
mvn -f backend/protocol/pom.xml test -Dtest=TcpSocketNetworkFramingTest
```
