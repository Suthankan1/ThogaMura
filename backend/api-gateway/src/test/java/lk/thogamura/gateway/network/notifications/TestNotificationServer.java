package lk.thogamura.gateway.network.notifications;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight test fixture hosting a real TCP ServerSocketChannel on loopback.
 * Used exclusively by integration tests to simulate server sends, split frames,
 * disconnections, and restarts on the same port.
 */
public class TestNotificationServer implements AutoCloseable {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocketChannel serverChannel;
    private volatile SocketChannel connectedClient;
    private Thread acceptThread;
    private int boundPort;

    public TestNotificationServer() throws IOException {
        this(0);
    }

    public TestNotificationServer(int port) throws IOException {
        bind(port);
    }

    public synchronized void start() {
        if (running.get()) {
            return;
        }
        running.set(true);
        acceptThread = new Thread(this::runAcceptLoop, "test-notification-server");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void bind(int port) throws IOException {
        serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress("127.0.0.1", port));
        InetSocketAddress local = (InetSocketAddress) serverChannel.getLocalAddress();
        this.boundPort = local.getPort();
    }

    private void runAcceptLoop() {
        while (running.get()) {
            try {
                SocketChannel client = serverChannel.accept();
                if (client != null) {
                    client.setOption(StandardSocketOptions.TCP_NODELAY, true);
                    this.connectedClient = client;
                }
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    public int getPort() {
        return boundPort;
    }

    public boolean hasConnectedClient() {
        SocketChannel client = this.connectedClient;
        return client != null && client.isOpen() && client.isConnected();
    }

    public synchronized void send(String raw) throws IOException {
        SocketChannel client = this.connectedClient;
        if (client == null || !client.isOpen()) {
            throw new IOException("No connected client to send to");
        }
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            client.write(buffer);
        }
    }

    public void sendNotification(long version) throws IOException {
        send("{\"v\":1,\"type\":\"CHANGED\",\"version\":" + version + "}\n");
    }

    public synchronized void disconnectClient() {
        SocketChannel client = this.connectedClient;
        this.connectedClient = null;
        if (client != null) {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void restartOnSamePort() throws IOException {
        stop();
        bind(boundPort);
        start();
    }

    @Override
    public synchronized void close() {
        stop();
    }

    public synchronized void stop() {
        running.set(false);
        disconnectClient();
        if (serverChannel != null) {
            try {
                serverChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (acceptThread != null && acceptThread.isAlive()) {
            acceptThread.interrupt();
        }
    }
}
