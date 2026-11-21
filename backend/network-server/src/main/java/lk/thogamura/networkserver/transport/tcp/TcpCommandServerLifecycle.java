package lk.thogamura.networkserver.transport.tcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring lifecycle adapter hosting the warehouse TCP command server.
 *
 * <p>Enables clean integration into the Spring application context lifecycle
 * without compromising pure JDK networking. Disabled by default
 * ({@code thogamura.command.server.enabled=false}) so tests do not bind ports unintentionally.</p>
 */
@Component
@ConditionalOnProperty(prefix = "thogamura.command.server", name = "enabled", havingValue = "true")
public class TcpCommandServerLifecycle implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(TcpCommandServerLifecycle.class);

    private final TcpCommandServerConfig config;
    private final CommandDispatcher dispatcher;
    private final javax.net.ServerSocketFactory serverSocketFactory;

    private TcpCommandServer server;
    private boolean running = false;

    @Autowired
    public TcpCommandServerLifecycle(
            @Value("${thogamura.command.server.host:127.0.0.1}") String host,
            @Value("${thogamura.command.server.port:7443}") int port,
            @Value("${thogamura.command.server.backlog:50}") int backlog,
            @Value("${thogamura.command.server.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${thogamura.command.server.frame-deadline-ms:10000}") int frameDeadlineMs,
            @Value("${thogamura.command.server.max-clients:50}") int maxClients,
            @Value("${thogamura.command.server.max-frame-bytes:65536}") int maxFrameBytes,
            @Value("${thogamura.command.server.allow-non-loopback-plaintext:false}") boolean allowNonLoopbackPlaintext,
            @Value("${thogamura.tls.server.enabled:${thogamura.tls.enabled:false}}") boolean tlsEnabled,
            @Autowired(required = false) CommandDispatcher dispatcher,
            @Autowired(required = false) javax.net.ServerSocketFactory serverSocketFactory) {
        this.config = new TcpCommandServerConfig(
                host,
                port,
                backlog,
                readTimeoutMs,
                frameDeadlineMs,
                maxClients,
                maxFrameBytes,
                allowNonLoopbackPlaintext,
                tlsEnabled
        );
        this.dispatcher = (dispatcher != null) ? dispatcher : new DefaultCommandDispatcher();
        this.serverSocketFactory = (serverSocketFactory != null)
                ? serverSocketFactory
                : javax.net.ServerSocketFactory.getDefault();
    }

    public TcpCommandServerLifecycle(
            TcpCommandServerConfig config,
            CommandDispatcher dispatcher,
            javax.net.ServerSocketFactory serverSocketFactory) {
        this.config = config;
        this.dispatcher = (dispatcher != null) ? dispatcher : new DefaultCommandDispatcher();
        this.serverSocketFactory = (serverSocketFactory != null)
                ? serverSocketFactory
                : javax.net.ServerSocketFactory.getDefault();
    }

    public TcpCommandServerLifecycle(TcpCommandServerConfig config, CommandDispatcher dispatcher) {
        this(config, dispatcher, javax.net.ServerSocketFactory.getDefault());
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            server = new TcpCommandServer(config, dispatcher, serverSocketFactory);
            server.start();
            running = true;
            logger.info("TcpCommandServerLifecycle started warehouse TCP command listener on {}:{}",
                    config.getHost(), server.getPort());
        } catch (IOException e) {
            logger.error("Failed to start TCP command server on {}:{}", config.getHost(), config.getPort(), e);
            throw new IllegalStateException("Failed to start TCP command server", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
        logger.info("TcpCommandServerLifecycle stopped warehouse TCP command listener");
    }

    @Override
    public synchronized boolean isRunning() {
        return running && server != null && server.isRunning();
    }

    public TcpCommandServer getServer() {
        return server;
    }

    public int getPort() {
        return server != null ? server.getPort() : -1;
    }
}
