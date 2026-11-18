package lk.thogamura.gateway.network.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.FrameTooLargeException;
import lk.thogamura.protocol.tcp.MalformedFrameException;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import lk.thogamura.protocol.tcp.TruncatedFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.UUID;

/**
 * Blocking Java TCP command client for the Spring Boot API Gateway.
 *
 * <p>Uses standard JDK sockets ({@link Socket}) and stream framing to send business commands
 * and diagnostic inquiries to the central warehouse server. Strictly enforces Protocol v1
 * framing bounds, connection and read timeouts, and {@code requestId} response correlation.</p>
 *
 * <p>Accepts an injected {@link SocketFactory} (defaults to {@link SocketFactory#getDefault()}),
 * allowing Member 5 to integrate validated JSSE TLS ({@code SSLSocketFactory}) seamlessly
 * without changing the application message format or client logic.</p>
 */
public class TcpCommandClient {

    private static final Logger logger = LoggerFactory.getLogger(TcpCommandClient.class);

    private final TcpCommandClientConfig config;
    private final SocketFactory socketFactory;

    public TcpCommandClient(TcpCommandClientConfig config) {
        this(config, SocketFactory.getDefault());
    }

    public TcpCommandClient(TcpCommandClientConfig config, SocketFactory socketFactory) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory must not be null");
    }

    /**
     * Sends a command request over a real Java TCP socket with a generated UUID {@code requestId}
     * and awaits the correlated response envelope.
     *
     * @param type command type from {@link ProtocolInfo}
     * @param data command data payload object (or null)
     * @return parsed JSON response node
     * @throws CommandTransportException on network, framing, or correlation failures
     */
    public JsonNode sendCommand(String type, Object data) throws CommandTransportException {
        return sendCommand(type, UUID.randomUUID().toString(), data);
    }

    /**
     * Sends a command request over a real Java TCP socket with an explicit {@code requestId}
     * and awaits the correlated response envelope.
     *
     * @param type command type from {@link ProtocolInfo}
     * @param requestId canonical UUID v4 correlation identifier
     * @param data command data payload object (or null)
     * @return parsed JSON response node
     * @throws CommandTransportException on network, framing, or correlation failures
     */
    public JsonNode sendCommand(String type, String requestId, Object data) throws CommandTransportException {
        Objects.requireNonNull(type, "type must not be null");
        String effectiveRequestId = (requestId != null && !requestId.isBlank())
                ? requestId
                : UUID.randomUUID().toString();

        config.validate();

        String requestPayload = TcpMessageCodec.formatRequest(type, effectiveRequestId, data);
        logger.debug("Dispatching TCP command '{}' (requestId: '{}') to {}:{}",
                type, effectiveRequestId, config.getHost(), config.getPort());

        Socket socket = connectSocket(effectiveRequestId);

        try (socket;
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream();
             TcpFrameWriter writer = new TcpFrameWriter(out, config.getMaxFrameBytes());
             TcpFrameReader reader = new TcpFrameReader(in, config.getMaxFrameBytes())) {

            // Transmit framed request
            writer.writeFrame(requestPayload);

            // Await framed response with bounded SO_TIMEOUT
            String responsePayload;
            try {
                responsePayload = reader.readNextFrameString();
            } catch (SocketTimeoutException e) {
                logger.warn("Read timed out after {}ms awaiting response for requestId '{}'",
                        config.getReadTimeoutMs(), effectiveRequestId);
                throw new CommandTimeoutException(
                        "Read timed out after " + config.getReadTimeoutMs() + "ms awaiting response for requestId: "
                                + effectiveRequestId,
                        effectiveRequestId,
                        e
                );
            } catch (FrameTooLargeException e) {
                logger.error("Server returned frame exceeding limit ({} bytes) for requestId '{}'",
                        config.getMaxFrameBytes(), effectiveRequestId);
                throw new CommandTransportException(
                        "Server returned response frame exceeding maximum allowed limit ("
                                + config.getMaxFrameBytes() + " bytes)",
                        effectiveRequestId,
                        e
                );
            } catch (TruncatedFrameException e) {
                logger.warn("Server abruptly closed connection mid-frame for requestId '{}'", effectiveRequestId);
                throw new ServerUnavailableException(
                        "Server abruptly closed connection mid-frame for requestId: " + effectiveRequestId,
                        effectiveRequestId,
                        e
                );
            } catch (IOException e) {
                logger.error("Network I/O error reading response for requestId '{}': {}",
                        effectiveRequestId, e.getMessage(), e);
                throw new CommandTransportException(
                        "Network I/O error reading response for requestId: " + effectiveRequestId + ": " + e.getMessage(),
                        effectiveRequestId,
                        e
                );
            }

            if (responsePayload == null) {
                logger.warn("Server closed connection cleanly without returning response for requestId '{}'",
                        effectiveRequestId);
                throw new ServerUnavailableException(
                        "Warehouse server closed connection cleanly without returning a response for requestId: "
                                + effectiveRequestId,
                        effectiveRequestId
                );
            }

            // Parse and validate response JSON root and envelope
            JsonNode responseNode;
            try {
                responseNode = TcpMessageCodec.parseJsonObject(responsePayload);
                TcpMessageCodec.validateEnvelope(responseNode);
            } catch (MalformedFrameException e) {
                logger.error("Malformed response envelope from server for requestId '{}': {}",
                        effectiveRequestId, e.getMessage());
                throw new CommandTransportException(
                        "Received malformed response envelope from server: " + e.getMessage(),
                        effectiveRequestId,
                        e
                );
            }

            // Enforce strict request/response correlation
            String responseRequestId = responseNode.hasNonNull("requestId")
                    ? responseNode.get("requestId").asText()
                    : null;

            if (!effectiveRequestId.equals(responseRequestId)) {
                logger.error("Response correlation failure: expected '{}' but received '{}'",
                        effectiveRequestId, responseRequestId);
                throw new CommandCorrelationException(
                        "Response correlation failure: expected requestId '" + effectiveRequestId
                                + "' but server returned '" + responseRequestId + "'",
                        effectiveRequestId,
                        responseRequestId
                );
            }

            return responseNode;
        } catch (CommandTransportException e) {
            throw e;
        } catch (IOException e) {
            throw new CommandTransportException(
                    "Socket transmission error for requestId: " + effectiveRequestId + ": " + e.getMessage(),
                    effectiveRequestId,
                    e
            );
        }
    }

    private Socket connectSocket(String requestId) throws CommandTransportException {
        Socket socket = null;
        try {
            socket = socketFactory.createSocket();
            socket.setSoTimeout(config.getReadTimeoutMs());
            socket.connect(
                    new InetSocketAddress(config.getHost(), config.getPort()),
                    config.getConnectTimeoutMs()
            );
            if (socket instanceof javax.net.ssl.SSLSocket sslSocket) {
                sslSocket.startHandshake();
            }
            return socket;
        } catch (ConnectException | UnknownHostException | NoRouteToHostException e) {
            closeQuietly(socket);
            logger.warn("Warehouse server is unavailable at {}:{} for requestId '{}': {}",
                    config.getHost(), config.getPort(), requestId, e.getMessage());
            throw new ServerUnavailableException(
                    "Warehouse server is unavailable at " + config.getHost() + ":" + config.getPort()
                            + ": " + e.getMessage(),
                    requestId,
                    e
            );
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
            logger.warn("Connection timed out after {}ms connecting to {}:{} for requestId '{}'",
                    config.getConnectTimeoutMs(), config.getHost(), config.getPort(), requestId);
            throw new CommandTimeoutException(
                    "Connection timed out after " + config.getConnectTimeoutMs() + "ms connecting to "
                            + config.getHost() + ":" + config.getPort(),
                    requestId,
                    e
            );
        } catch (javax.net.ssl.SSLException e) {
            closeQuietly(socket);
            logger.error("TLS handshake failed connecting to {}:{} for requestId '{}': {}",
                    config.getHost(), config.getPort(), requestId, e.getMessage(), e);
            throw new CommandTransportException(
                    "TLS handshake failed connecting to warehouse server at " + config.getHost() + ":"
                            + config.getPort() + ": " + e.getMessage(),
                    requestId,
                    e
            );
        } catch (IOException e) {
            closeQuietly(socket);
            logger.error("Failed to establish TCP connection to {}:{} for requestId '{}': {}",
                    config.getHost(), config.getPort(), requestId, e.getMessage(), e);
            throw new ServerUnavailableException(
                    "Failed to establish connection to warehouse server at " + config.getHost() + ":"
                            + config.getPort() + ": " + e.getMessage(),
                    requestId,
                    e
            );
        }
    }

    /**
     * Executes the frozen diagnostic {@code PING} command against the warehouse server,
     * validating that the server is online and returning {@code status: "PONG"}.
     *
     * @return response JSON node containing {@code status: "PONG"} and {@code data.serverTime}
     * @throws CommandTransportException on network, correlation, or server failure
     */
    public JsonNode ping() throws CommandTransportException {
        JsonNode response = sendCommand(ProtocolInfo.COMMAND_PING, null);
        String status = response.hasNonNull("status") ? response.get("status").asText() : "";
        if (!ProtocolInfo.STATUS_PONG.equals(status)) {
            String requestId = response.hasNonNull("requestId") ? response.get("requestId").asText() : "";
            throw new CommandTransportException(
                    "Expected status PONG but received: " + status,
                    requestId
            );
        }
        return response;
    }

    public TcpCommandClientConfig getConfig() {
        return config;
    }

    public SocketFactory getSocketFactory() {
        return socketFactory;
    }

    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }
}
