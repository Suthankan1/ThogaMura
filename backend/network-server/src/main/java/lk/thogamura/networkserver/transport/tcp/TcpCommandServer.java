package lk.thogamura.networkserver.transport.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.networkserver.concurrent.InventoryWorkerPool;
import lk.thogamura.protocol.tcp.FrameTooLargeException;
import lk.thogamura.protocol.tcp.MalformedFrameException;
import lk.thogamura.protocol.tcp.ProtocolVersionException;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import lk.thogamura.protocol.tcp.TruncatedFrameException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * High-performance, bounded Java TCP command listener for the central warehouse.
 *
 * <p>Uses standard blocking JDK sockets ({@link ServerSocket}, {@link Socket}) backed by a
 * bounded worker thread pool. Strictly enforces Protocol v1 framing, timeouts, and request
 * correlation rules defined in {@code docs/contracts/NETWORK_PROTOCOL_V1.md}.</p>
 *
 * <p>Supports {@link ServerSocketFactory} injection so Member 5 can seamlessly integrate
 * JSSE TLS ({@code SSLServerSocket}) without modifying the command dispatcher or framing loop.</p>
 */
public class TcpCommandServer {

    private static final Logger logger = LoggerFactory.getLogger(TcpCommandServer.class);

    private final TcpCommandServerConfig config;
    private final CommandDispatcher dispatcher;
    private final ServerSocketFactory serverSocketFactory;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    private ServerSocket serverSocket;
    private ExecutorService workerPool;
    private Thread acceptorThread;
    private volatile boolean running = false;

    public TcpCommandServer(TcpCommandServerConfig config) {
        this(config, new DefaultCommandDispatcher(), ServerSocketFactory.getDefault());
    }

    public TcpCommandServer(TcpCommandServerConfig config, CommandDispatcher dispatcher) {
        this(config, dispatcher, ServerSocketFactory.getDefault());
    }

    public TcpCommandServer(
            TcpCommandServerConfig config,
            CommandDispatcher dispatcher,
            ServerSocketFactory serverSocketFactory) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        this.serverSocketFactory = Objects.requireNonNull(serverSocketFactory, "serverSocketFactory must not be null");
    }

    /**
     * Binds the server socket and starts the background client accept loop and worker pool.
     *
     * @throws IOException if binding fails or network interface cannot be opened
     */
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        config.validate();

        serverSocket = serverSocketFactory.createServerSocket();
        if (config.isTlsEnabled() && !(serverSocket instanceof javax.net.ssl.SSLServerSocket)) {
            serverSocket.close();
            throw new IllegalStateException("TLS command listener requires an SSLServerSocket");
        }
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getHost(), config.getPort()), config.getBacklog());
        running = true;

        int maxClients = config.getMaxClients();
        workerPool = new InventoryWorkerPool(maxClients, maxClients, maxClients, new ThreadPoolExecutor.AbortPolicy());

        acceptorThread = new Thread(this::acceptLoop, "tcp-cmd-server-acceptor");
        acceptorThread.setDaemon(true);
        acceptorThread.start();

        logger.info("TCP command server started on {}:{} [readTimeout={}ms, maxClients={}]",
                config.getHost(), serverSocket.getLocalPort(), config.getReadTimeoutMs(), config.getMaxClients());
    }

    /**
     * Accepts incoming client TCP connections and submits them to the worker pool.
     */
    private void acceptLoop() {
        while (running && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();

                // Security check for plaintext binding: verify remote client if restricted
                boolean isTls = serverSocket instanceof javax.net.ssl.SSLServerSocket;
                if (!isTls && !clientSocket.getInetAddress().isLoopbackAddress()) {
                    logger.warn("Rejecting non-loopback plaintext connection attempt from {}",
                            clientSocket.getRemoteSocketAddress());
                    closeQuietly(clientSocket);
                    continue;
                }

                if (activeSockets.size() >= config.getMaxClients()) {
                    logger.warn("Active client connections ({}) reached max capacity ({}); rejecting connection from {}",
                            activeSockets.size(), config.getMaxClients(), clientSocket.getRemoteSocketAddress());
                    closeQuietly(clientSocket);
                    continue;
                }

                activeSockets.add(clientSocket);
                try {
                    workerPool.submit(() -> handleClient(clientSocket));
                } catch (RejectedExecutionException rejected) {
                    activeSockets.remove(clientSocket);
                    logger.warn("Worker pool saturated; rejecting connection from {}", clientSocket.getRemoteSocketAddress());
                    closeQuietly(clientSocket);
                }
            } catch (SocketException e) {
                // Normal when serverSocket.close() is called during shutdown
                if (!running) {
                    break;
                }
                logger.debug("SocketException in accept loop: {}", e.getMessage());
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                logger.error("I/O error accepting client connection: {}", e.getMessage(), e);
            }
        }
        logger.debug("TCP command server accept loop terminated");
    }

    /**
     * Handles command frames from a single connected client socket.
     * Reuses {@link TcpFrameReader} and {@link TcpFrameWriter} to enforce 64 KB framing bounds.
     */
    private void handleClient(Socket socket) {
        String clientAddress = String.valueOf(socket.getRemoteSocketAddress());
        logger.info("Accepted command socket connection from {}", clientAddress);
        try {
            socket.setSoTimeout(config.getReadTimeoutMs());

            if (socket instanceof javax.net.ssl.SSLSocket sslSocket) {
                try {
                    sslSocket.startHandshake();
                } catch (javax.net.ssl.SSLException e) {
                    logger.warn("TLS handshake failed with client {}: {}", clientAddress, e.getMessage());
                    closeQuietly(socket);
                    return;
                }
            }

            try (socket;
                 InputStream rawIn = socket.getInputStream();
                 FrameDeadlineInputStream in = new FrameDeadlineInputStream(
                         rawIn, socket, config.getReadTimeoutMs(), config.getFrameDeadlineMs()
                 );
                 OutputStream out = socket.getOutputStream();
                 TcpFrameReader reader = new TcpFrameReader(in, config.getMaxFrameBytes());
                 TcpFrameWriter writer = new TcpFrameWriter(out, config.getMaxFrameBytes())) {

                while (running && !socket.isClosed()) {
                    String frame;
                    try {
                        frame = reader.readNextFrameString();
                    } catch (SocketTimeoutException e) {
                        // Read timeout while waiting for incoming frame: close idle or stalled socket
                        logger.debug("Read timeout ({}ms) reached on client {}; closing connection",
                                config.getReadTimeoutMs(), clientAddress);
                        break;
                    } catch (FrameTooLargeException e) {
                        logger.warn("Client {} sent frame exceeding max bounds ({} bytes): {}",
                                clientAddress, config.getMaxFrameBytes(), e.getMessage());
                        try {
                            writer.writeFrame(TcpMessageCodec.formatErrorResponse(
                                    null,
                                    ProtocolInfo.ERROR_VALIDATION,
                                    "Frame exceeded maximum allowed limit of " + config.getMaxFrameBytes() + " bytes"
                            ));
                        } catch (IOException ignored) {}
                        break; // Terminate connection as stream bytes were discarded
                    } catch (TruncatedFrameException e) {
                        logger.warn("Client {} abruptly terminated stream mid-frame ({} bytes accumulated)",
                                clientAddress, e.getAccumulatedBytes());
                        break;
                    } catch (MalformedFrameException e) {
                        logger.warn("Client {} sent malformed frame: {}", clientAddress, e.getMessage());
                        writer.writeFrame(TcpMessageCodec.formatErrorResponse(
                                e.getRequestId(),
                                e.getErrorCode(),
                                e.getMessage()
                        ));
                        continue;
                    }

                    if (frame == null) {
                        // Clean client EOF at message boundary
                        logger.debug("Client {} disconnected cleanly", clientAddress);
                        break;
                    }

                    // Detect the supported profile and validate its JSON envelope.
                    JsonNode requestNode;
                    int responseVersion = ProtocolInfo.WIRE_VERSION;
                    try {
                        requestNode = TcpMessageCodec.parseJsonObject(frame);
                        responseVersion = dispatcher.expectedVersion(requestNode.path("v").asInt());
                        TcpMessageCodec.validateEnvelope(requestNode, responseVersion);
                    } catch (ProtocolVersionException e) {
                        logger.warn("Client {} sent unsupported protocol version: {}", clientAddress, e.getMessage());
                        writer.writeFrame(TcpMessageCodec.formatErrorResponse(
                                e.getRequestId(),
                                ProtocolInfo.ERROR_UNSUPPORTED_VERSION,
                                e.getMessage()
                        ));
                        continue;
                    } catch (MalformedFrameException e) {
                        logger.warn("Client {} envelope validation failed: {}", clientAddress, e.getMessage());
                        writer.writeFrame(TcpMessageCodec.formatErrorResponse(
                                responseVersion, e.getRequestId(),
                                e.getErrorCode(),
                                e.getMessage()
                        ));
                        continue;
                    }

                    // Dispatch to business/diagnostic command handler
                    logger.info("Dispatching command '{}' (requestId: '{}') from {}",
                            requestNode.path("type").asText(), requestNode.path("requestId").asText(), clientAddress);
                    String responseJson = dispatcher.dispatch(requestNode);
                    byte[] payloadBytes = responseJson.getBytes(StandardCharsets.UTF_8);
                    if (payloadBytes.length + 1 > ProtocolInfo.MAX_FRAME_BYTES) {
                        logger.warn("Outgoing response for command '{}' (requestId: '{}') exceeded maximum frame limit of {} bytes (actual: {} bytes)",
                                requestNode.path("type").asText(), requestNode.path("requestId").asText(),
                                ProtocolInfo.MAX_FRAME_BYTES, payloadBytes.length + 1);
                        String safeError = TcpMessageCodec.formatErrorResponse(
                                responseVersion, requestNode.path("requestId").asText(),
                                ProtocolInfo.ERROR_INTERNAL,
                                "Response size exceeds maximum TCP frame limit of " + ProtocolInfo.MAX_FRAME_BYTES + " bytes"
                        );
                        writer.writeFrame(safeError);
                    } else {
                        writer.writeFrame(payloadBytes);
                    }
                }
            }
        } catch (IOException e) {
            if (running && !socket.isClosed()) {
                logger.debug("I/O error communicating with client {}: {}", clientAddress, e.getMessage());
            }
        } finally {
            activeSockets.remove(socket);
            closeQuietly(socket);
        }
    }

    /**
     * Gracefully stops the server socket, terminates active client connections, and shuts down the worker pool.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        logger.info("Stopping TCP command server on port {}...", getPort());

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                logger.debug("Error closing server socket: {}", e.getMessage());
            }
        }

        if (acceptorThread != null) {
            acceptorThread.interrupt();
        }

        for (Socket s : activeSockets) {
            closeQuietly(s);
        }
        activeSockets.clear();

        if (workerPool != null) {
            workerPool.shutdown();
            try {
                if (!workerPool.awaitTermination(2, TimeUnit.SECONDS)) {
                    workerPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logger.info("TCP command server stopped successfully.");
    }

    private void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    public int getPort() {
        return (serverSocket != null && !serverSocket.isClosed()) ? serverSocket.getLocalPort() : -1;
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getActiveClientCount() {
        return activeSockets.size();
    }

    /**
     * Standalone CLI runner to launch the TCP command server in an independent JVM process.
     *
     * <p>Outputs {@code SERVER_READY:<port>} on stdout upon successful socket binding,
     * allowing external orchestrators, separate JVM integration tests, and manual demonstrations
     * to capture the allocated port and begin transmission.</p>
     *
     * @param args optional arguments: [port] [host]
     * @throws IOException on startup or socket binding errors
     */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : ProtocolInfo.DEFAULT_TLS_COMMAND_PORT;
        String host = args.length > 1 ? args[1] : "127.0.0.1";
        String keystorePath = args.length > 2 ? args[2] : System.getProperty("thogamura.tls.keystore-path", System.getenv("THOGAMURA_TLS_KEYSTORE_PATH"));
        String keystorePass = args.length > 3 ? args[3] : System.getProperty("thogamura.tls.keystore-password", System.getenv("THOGAMURA_TLS_KEYSTORE_PASSWORD"));

        boolean tls = keystorePath != null && !keystorePath.isBlank();
        TcpCommandServerConfig config = new TcpCommandServerConfig(
                host, port, 50, ProtocolInfo.DEFAULT_READ_TIMEOUT_MS, 50, ProtocolInfo.MAX_FRAME_BYTES, false, tls);

        ServerSocketFactory socketFactory = ServerSocketFactory.getDefault();
        if (tls) {
            char[] pass = (keystorePass != null) ? keystorePass.toCharArray() : new char[0];
            socketFactory = lk.thogamura.networkserver.security.TlsServerSocketFactory.fromKeystore(
                    java.nio.file.Path.of(keystorePath), pass, "PKCS12", new String[]{"TLSv1.3", "TLSv1.2"});
        }

        TcpCommandServer server = new TcpCommandServer(config, new DefaultCommandDispatcher(), socketFactory);
        server.start();

        System.out.println("SERVER_READY:" + server.getPort());
        System.out.flush();

        CountDownLatch shutdownLatch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            shutdownLatch.countDown();
        }));

        try {
            while (server.isRunning()) {
                int read = System.in.read();
                if (read == -1) {
                    shutdownLatch.await();
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            server.stop();
        }
    }
}
