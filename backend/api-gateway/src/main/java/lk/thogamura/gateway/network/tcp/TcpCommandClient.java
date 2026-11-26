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
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Blocking Java TCP command client for the Spring Boot API Gateway.
 *
 * <p>Uses standard JDK sockets ({@link Socket}) and stream framing to send business commands
 * and diagnostic inquiries to the central warehouse server. Strictly enforces Protocol v1
 * framing bounds, connection and read timeouts, strict {@code requestId} response correlation,
 * and bounded safe same-requestId retries with finite backoff.</p>
 *
 * <p>Accepts an injected {@link SocketFactory} (defaults to {@link SocketFactory#getDefault()}),
 * allowing Member 5 to integrate validated JSSE TLS ({@code SSLSocketFactory}) seamlessly
 * without changing the application message format or client logic.</p>
 */
public class TcpCommandClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TcpCommandClient.class);
    private static final int COMMAND_BUDGET_MS = 12_000;
    private static final int SUPPLIER_RESPONSE_TIMEOUT_MS = 9_000;
    private static final long POOL_IDLE_TTL_NANOS = TimeUnit.SECONDS.toNanos(20);

    private static int configuredTimeout(int limit) {
        return limit == 0 ? COMMAND_BUDGET_MS : limit;
    }

    private final TcpCommandClientConfig config;
    private final SocketFactory socketFactory;
    private final Sleeper sleeper;
    private final ConcurrentLinkedDeque<PooledConnection> connectionPool = new ConcurrentLinkedDeque<>();
    private final AtomicInteger poolSize = new AtomicInteger(0);
    private final AtomicLong createdConnectionCount = new AtomicLong(0);
    private final AtomicLong reusedConnectionCount = new AtomicLong(0);
    private volatile boolean closed = false;

    public TcpCommandClient(TcpCommandClientConfig config) {
        this(config, SocketFactory.getDefault(), Thread::sleep);
    }

    public TcpCommandClient(TcpCommandClientConfig config, SocketFactory socketFactory) {
        this(config, socketFactory, Thread::sleep);
    }

    public TcpCommandClient(TcpCommandClientConfig config, SocketFactory socketFactory, Sleeper sleeper) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    /**
     * Sends a command request over a real Java TCP socket with a generated UUID {@code requestId}
     * and awaits the correlated response envelope, applying configured bounded retries for transient failures.
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
     * <p>Retries eligible transport failures (such as server disconnects or socket timeouts)
     * up to {@code config.getMaxRetries()} using the <b>identical</b> {@code requestId} and
     * exact original payload to leverage the warehouse server's durable command receipts.</p>
     *
     * @param type command type from {@link ProtocolInfo}
     * @param requestId canonical UUID v4 correlation identifier
     * @param data command data payload object (or null)
     * @return parsed JSON response node
     * @throws CommandTransportException on network, framing, or correlation failures
     */
    public JsonNode sendCommand(String type, String requestId, Object data) throws CommandTransportException {
        return sendCommand(ProtocolInfo.WIRE_VERSION, type, requestId, data);
    }

    public JsonNode sendCommand(int version, String type, String requestId, Object data) throws CommandTransportException {
        Objects.requireNonNull(type, "type must not be null");
        String effectiveRequestId = (requestId != null && !requestId.isBlank())
                ? requestId
                : UUID.randomUUID().toString();

        config.validate();

        String requestPayload = TcpMessageCodec.formatRequest(version, type, effectiveRequestId, data);

        boolean supplier = ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK.equals(type);
        int maxRetries = supplier ? 0 : config.getMaxRetries();
        int responseTimeout = supplier ? SUPPLIER_RESPONSE_TIMEOUT_MS : configuredTimeout(config.getReadTimeoutMs());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMMAND_BUDGET_MS);
        long currentBackoffMs = config.getRetryBackoffMs();
        CommandTransportException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                logger.info("Retrying TCP command '{}' (attempt {} of {}, requestId: '{}') after backoff {}ms",
                        type, attempt, maxRetries, effectiveRequestId, currentBackoffMs);
                try {
                    if (TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()) <= currentBackoffMs) {
                        throw deadlineExceeded(effectiveRequestId);
                    }
                    sleeper.sleep(currentBackoffMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CommandTransportException(
                            "Interrupted during retry backoff for requestId: " + effectiveRequestId,
                            effectiveRequestId,
                            e
                    );
                }
                currentBackoffMs = Math.min(
                        (long) (currentBackoffMs * config.getRetryBackoffMultiplier()),
                        config.getMaxRetryBackoffMs()
                );
            }

            try {
                return executeSingleAttempt(version, type, effectiveRequestId, requestPayload, responseTimeout, deadline);
            } catch (CommandCorrelationException e) {
                // Mismatched response requestId is a severe protocol failure; never mistake for success and do not retry
                logger.error("Response correlation failure for requestId '{}' (received '{}'): not retrying",
                        e.getExpectedRequestId(), e.getReceivedRequestId());
                throw e;
            } catch (CommandTransportException e) {
                lastException = e;
                if (!isEligibleForRetry(e) || attempt == maxRetries) {
                    logger.warn("TCP command '{}' (requestId: '{}') failed on attempt {} of {} and will not be retried: {}",
                            type, effectiveRequestId, attempt + 1, maxRetries + 1, e.getMessage());
                    throw e;
                }
                logger.warn("Transient transport failure on attempt {}/{} for requestId '{}': {}",
                        attempt + 1, maxRetries + 1, effectiveRequestId, e.getMessage());
            }
        }

        if (lastException != null) {
            throw lastException;
        }
        throw new CommandTransportException(
                "Command failed without response for requestId: " + effectiveRequestId,
                effectiveRequestId
        );
    }

    /**
     * Executes a single request attempt without retries.
     *
     * @param type command type from {@link ProtocolInfo}
     * @param requestId canonical correlation identifier
     * @param data command payload object (or null)
     * @return parsed JSON response node
     * @throws CommandTransportException on network, framing, or correlation failures
     */
    public JsonNode sendCommandOnce(String type, String requestId, Object data) throws CommandTransportException {
        Objects.requireNonNull(type, "type must not be null");
        String effectiveRequestId = (requestId != null && !requestId.isBlank())
                ? requestId
                : UUID.randomUUID().toString();
        config.validate();
        String requestPayload = TcpMessageCodec.formatRequest(type, effectiveRequestId, data);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMMAND_BUDGET_MS);
        return executeSingleAttempt(ProtocolInfo.WIRE_VERSION, type, effectiveRequestId, requestPayload, configuredTimeout(config.getReadTimeoutMs()), deadline);
    }

    private boolean isEligibleForRetry(CommandTransportException e) {
        if (e instanceof CommandCorrelationException) {
            return false;
        }
        Throwable cause = e.getCause();
        if (cause instanceof javax.net.ssl.SSLException) {
            // Cryptographic / TLS certificate failures must fail-closed immediately without retry
            return false;
        }
        if (cause instanceof FrameTooLargeException || cause instanceof MalformedFrameException) {
            return false;
        }
        return true;
    }

    private JsonNode executeSingleAttempt(int version, String type, String effectiveRequestId, String requestPayload,
                                          int responseTimeoutMs, long commandDeadline)
            throws CommandTransportException {
        PooledConnection conn = borrowConnection(effectiveRequestId, commandDeadline);
        if (conn.reused()) {
            try {
                return executeOnConnection(conn, version, type, effectiveRequestId, requestPayload, responseTimeoutMs, commandDeadline);
            } catch (ServerUnavailableException staleError) {
                // Pooled socket was closed by server while idle; transparently open a fresh socket once
                logger.debug("Pooled socket went stale for '{}' (requestId: '{}'), opening fresh connection", type, effectiveRequestId);
                closePooledConnection(conn);
                PooledConnection fresh = openFreshConnection(effectiveRequestId, commandDeadline);
                return executeOnConnection(fresh, version, type, effectiveRequestId, requestPayload, responseTimeoutMs, commandDeadline);
            }
        }
        return executeOnConnection(conn, version, type, effectiveRequestId, requestPayload, responseTimeoutMs, commandDeadline);
    }

    private JsonNode executeOnConnection(PooledConnection conn, int version, String type, String effectiveRequestId,
                                         String requestPayload, int responseTimeoutMs, long commandDeadline)
            throws CommandTransportException {
        logger.debug("Dispatching TCP command attempt for '{}' (requestId: '{}', reused={}) to {}:{}",
                type, effectiveRequestId, conn.reused(), config.getHost(), config.getPort());

        long responseDeadline = Math.min(commandDeadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMs));
        conn.setDeadline(responseDeadline);
        boolean keepAlive = false;

        try {
            // Transmit framed request
            conn.writer().writeFrame(requestPayload);

            // Await framed response with bounded SO_TIMEOUT
            String responsePayload;
            try {
                responsePayload = conn.reader().readNextFrameString();
            } catch (SocketTimeoutException e) {
                logger.warn("Response deadline expired for requestId '{}'", effectiveRequestId);
                throw new CommandTimeoutException(
                        "Read timed out waiting for a complete response for requestId: " + effectiveRequestId,
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
            } catch (SocketException e) {
                logger.warn("Server connection reset or closed during read for requestId '{}': {}",
                        effectiveRequestId, e.getMessage());
                throw new ServerUnavailableException(
                        "Warehouse server dropped connection prematurely for requestId: "
                                + effectiveRequestId + ": " + e.getMessage(),
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
                TcpMessageCodec.validateEnvelope(responseNode, version);
                if (!ProtocolInfo.TYPE_RESULT.equals(responseNode.path("type").asText()) ||
                        !responseNode.path("status").isTextual() || responseNode.path("status").textValue().isBlank()) {
                    throw new MalformedFrameException("Expected a RESULT envelope with a status", ProtocolInfo.ERROR_VALIDATION);
                }
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

            keepAlive = config.getMaxPooledConnections() > 0 && !closed && !conn.socket().isClosed();
            return responseNode;
        } catch (CommandTransportException e) {
            throw e;
        } catch (IOException e) {
            throw new CommandTransportException(
                    "Socket transmission error for requestId: " + effectiveRequestId + ": " + e.getMessage(),
                    effectiveRequestId,
                    e
            );
        } finally {
            if (keepAlive) {
                returnToPool(conn);
            } else {
                closePooledConnection(conn);
            }
        }
    }

    private PooledConnection borrowConnection(String requestId, long deadline) throws CommandTransportException {
        if (config.getMaxPooledConnections() > 0 && !closed) {
            long now = System.nanoTime();
            PooledConnection candidate;
            while ((candidate = connectionPool.pollFirst()) != null) {
                poolSize.decrementAndGet();
                if (!candidate.isUsable(now)) {
                    closePooledConnection(candidate);
                    continue;
                }
                reusedConnectionCount.incrementAndGet();
                return candidate.asReused();
            }
        }
        return openFreshConnection(requestId, deadline);
    }

    private PooledConnection openFreshConnection(String requestId, long deadline) throws CommandTransportException {
        Socket socket = connectSocket(requestId, deadline);
        try {
            OutputStream out = socket.getOutputStream();
            DeadlineInputStream in = new DeadlineInputStream(socket, socket.getInputStream());
            TcpFrameWriter writer = new TcpFrameWriter(out, config.getMaxFrameBytes());
            TcpFrameReader reader = new TcpFrameReader(in, config.getMaxFrameBytes());
            createdConnectionCount.incrementAndGet();
            return new PooledConnection(socket, in, writer, reader, false, System.nanoTime());
        } catch (IOException e) {
            closeQuietly(socket);
            throw new CommandTransportException(
                    "Socket stream initialization error for requestId: " + requestId + ": " + e.getMessage(),
                    requestId,
                    e
            );
        }
    }

    private void returnToPool(PooledConnection conn) {
        if (closed || config.getMaxPooledConnections() <= 0 || conn.socket().isClosed()) {
            closePooledConnection(conn);
            return;
        }
        while (true) {
            int current = poolSize.get();
            if (current >= config.getMaxPooledConnections()) {
                closePooledConnection(conn);
                return;
            }
            if (poolSize.compareAndSet(current, current + 1)) {
                connectionPool.offerFirst(conn.touched(System.nanoTime()));
                if (closed) {
                    close();
                }
                return;
            }
        }
    }

    private void closePooledConnection(PooledConnection conn) {
        if (conn == null) return;
        try {
            conn.writer().close();
        } catch (IOException ignored) {}
        try {
            conn.reader().close();
        } catch (IOException ignored) {}
        closeQuietly(conn.socket());
    }

    @Override
    public void close() {
        closed = true;
        PooledConnection conn;
        while ((conn = connectionPool.pollFirst()) != null) {
            poolSize.decrementAndGet();
            closePooledConnection(conn);
        }
    }

    public int getPooledConnectionCount() {
        return Math.max(0, poolSize.get());
    }

    public long getCreatedConnectionCount() {
        return createdConnectionCount.get();
    }

    public long getReusedConnectionCount() {
        return reusedConnectionCount.get();
    }

    private static final class DeadlineInputStream extends FilterInputStream {
        private final Socket socket;
        private volatile long deadlineNanos;

        DeadlineInputStream(Socket socket, InputStream in) {
            super(in);
            this.socket = socket;
        }

        void setDeadlineNanos(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public int read() throws IOException {
            socket.setSoTimeout(remainingMillis(deadlineNanos, Integer.MAX_VALUE));
            return in.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            socket.setSoTimeout(remainingMillis(deadlineNanos, Integer.MAX_VALUE));
            return in.read(bytes, offset, length);
        }
    }

    private record PooledConnection(
            Socket socket,
            DeadlineInputStream in,
            TcpFrameWriter writer,
            TcpFrameReader reader,
            boolean reused,
            long lastUsedNanos) {
        void setDeadline(long deadlineNanos) {
            in.setDeadlineNanos(deadlineNanos);
        }

        boolean isUsable(long nowNanos) {
            return !socket.isClosed() && socket.isConnected() && !socket.isInputShutdown() && !socket.isOutputShutdown()
                    && (nowNanos - lastUsedNanos) <= POOL_IDLE_TTL_NANOS;
        }

        PooledConnection asReused() {
            return reused ? this : new PooledConnection(socket, in, writer, reader, true, lastUsedNanos);
        }

        PooledConnection touched(long nowNanos) {
            return new PooledConnection(socket, in, writer, reader, true, nowNanos);
        }
    }

    private static int remainingMillis(long deadline, int ceiling) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new SocketTimeoutException("Warehouse command deadline exceeded");
        return (int) Math.min(ceiling, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }

    private static CommandTimeoutException deadlineExceeded(String requestId) {
        return new CommandTimeoutException("Warehouse command deadline exceeded", requestId, null);
    }

    private Socket connectSocket(String requestId, long deadline) throws CommandTransportException {
        Socket socket = null;
        try {
            socket = socketFactory.createSocket();
            socket.connect(
                    new InetSocketAddress(config.getHost(), config.getPort()),
                    remainingMillis(deadline, configuredTimeout(config.getConnectTimeoutMs()))
            );
            socket.setSoTimeout(remainingMillis(deadline, configuredTimeout(config.getReadTimeoutMs())));
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
            logger.warn("Warehouse connection or TLS handshake timed out for requestId '{}'", requestId);
            throw new CommandTimeoutException(
                    "Warehouse connection or TLS handshake timed out",
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
        return requirePong(sendCommand(ProtocolInfo.COMMAND_PING, null));
    }

    public JsonNode pingOnce() throws CommandTransportException {
        return requirePong(sendCommandOnce(ProtocolInfo.COMMAND_PING, null, null));
    }

    private static JsonNode requirePong(JsonNode response) throws CommandTransportException {
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

    public Sleeper getSleeper() {
        return sleeper;
    }

    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * Standalone CLI runner to execute a command from the API Gateway client in an independent JVM process.
     *
     * <p>Usage: {@code java -cp ... TcpCommandClient [host] [port] [command]}</p>
     *
     * @param args optional arguments: [0]=host (default: 127.0.0.1), [1]=port (default: 7443), [2]=command (default: PING)
     */
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : ProtocolInfo.DEFAULT_TLS_COMMAND_PORT;
        String command = args.length > 2 ? args[2] : ProtocolInfo.COMMAND_PING;

        long pid = ProcessHandle.current().pid();
        System.out.println("GATEWAY_CLIENT_READY: PID=" + pid + " target=" + host + ":" + port);
        System.out.flush();

        TcpCommandClientConfig config = new TcpCommandClientConfig(host, port);
        TcpCommandClient client = new TcpCommandClient(config);

        try {
            String requestId = UUID.randomUUID().toString();
            System.out.println("CLIENT_REQUEST_DISPATCHED: requestId=" + requestId + " type=" + command);
            JsonNode response = client.sendCommand(command, requestId, null);
            System.out.println("CLIENT_RESPONSE_RECEIVED: " + response.toString());
        } catch (Exception e) {
            System.err.println("CLIENT_COMMAND_FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
