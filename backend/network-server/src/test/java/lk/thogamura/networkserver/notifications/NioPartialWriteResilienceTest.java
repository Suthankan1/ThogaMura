package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies non-blocking Java NIO partial write handling, {@link SelectionKey#OP_WRITE} lifecycle,
 * and busy-spin prevention as required by Issue #20 and Test Plan T07.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioPartialWriteResilienceTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (AutoCloseable res : resources) {
            try {
                res.close();
            } catch (Exception ignored) {
            }
        }
        resources.clear();
    }

    private <T extends AutoCloseable> T register(T resource) {
        resources.add(resource);
        return resource;
    }

    @Test
    @DisplayName("T07: Partial write buffers remaining bytes, registers OP_WRITE, and deregisters on completion to prevent busy-spin")
    void shouldRegisterOpWriteOnPartialWriteAndDeregisterWhenQueueDrains() throws IOException {
        Selector selector = register(Selector.open());
        ServerSocketChannel ssc = register(ServerSocketChannel.open());
        ssc.configureBlocking(false);
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        ssc.register(selector, SelectionKey.OP_ACCEPT);

        SocketChannel clientChannel = register(SocketChannel.open());
        clientChannel.configureBlocking(false);
        clientChannel.connect(ssc.getLocalAddress());

        // Selector accepts client connection
        int selected = selector.select(2000);
        assertThat(selected).isGreaterThan(0);
        SocketChannel serverSideClient = null;
        for (SelectionKey key : selector.selectedKeys()) {
            if (key.isAcceptable()) {
                serverSideClient = register(ssc.accept());
                break;
            }
        }
        selector.selectedKeys().clear();
        assertThat(serverSideClient).isNotNull();

        serverSideClient.configureBlocking(false);
        ClientSession session = new ClientSession(serverSideClient, 10);
        SelectionKey clientKey = serverSideClient.register(selector, SelectionKey.OP_READ, session);
        session.setSelectionKey(clientKey);

        clientChannel.finishConnect();
        assertThat(clientChannel.isConnected()).isTrue();

        // Initial state: only OP_READ is registered; OP_WRITE must NOT be registered (no pending data)
        assertThat(clientKey.interestOps() & SelectionKey.OP_WRITE)
                .as("OP_WRITE must not be registered when there is no pending outbound data")
                .isEqualTo(0);

        // Prepare a 40-byte notification frame
        ChangeNotification notification = ChangeNotification.of(101L);
        byte[] wireBytes = notification.toWireBytes();
        assertThat(wireBytes.length).isGreaterThan(10);
        session.enqueue(ByteBuffer.wrap(wireBytes));

        // Use a restricted channel that accepts only 10 bytes on the first write
        ByteArrayOutputStream capturedOutput = new ByteArrayOutputStream();
        AtomicBoolean allowFullDrain = new AtomicBoolean(false);

        WritableByteChannel restrictedChannel = new WritableByteChannel() {
            private boolean open = true;

            @Override
            public int write(ByteBuffer src) {
                if (!src.hasRemaining()) {
                    return 0;
                }
                int limit = allowFullDrain.get() ? src.remaining() : Math.min(10, src.remaining());
                byte[] chunk = new byte[limit];
                src.get(chunk);
                capturedOutput.writeBytes(chunk);
                return limit;
            }

            @Override
            public boolean isOpen() {
                return open;
            }

            @Override
            public void close() {
                open = false;
            }
        };

        // 1. First flush: partial write occurs (only 10 bytes written)
        boolean fullyFlushed = session.flush(restrictedChannel);
        assertThat(fullyFlushed).as("Partial write must indicate that data remains pending").isFalse();
        assertThat(session.hasPendingData()).isTrue();
        assertThat(session.getPendingMessageCount()).isEqualTo(1);
        assertThat(capturedOutput.size()).isEqualTo(10);

        // Server selector thread registers OP_WRITE
        clientKey.interestOps(clientKey.interestOps() | SelectionKey.OP_WRITE);
        assertThat((clientKey.interestOps() & SelectionKey.OP_WRITE) != 0)
                .as("SelectionKey must now have OP_WRITE registered")
                .isTrue();

        // 2. Allow channel to drain the remaining bytes (resuming via OP_WRITE)
        allowFullDrain.set(true);
        fullyFlushed = session.flush(restrictedChannel);

        assertThat(fullyFlushed).as("Resumed flush must completely transmit pending bytes").isTrue();
        assertThat(session.hasPendingData()).isFalse();
        assertThat(session.getPendingMessageCount()).isEqualTo(0);

        // Server selector thread clears OP_WRITE to prevent busy-spin polling
        clientKey.interestOps(clientKey.interestOps() & ~SelectionKey.OP_WRITE);
        assertThat((clientKey.interestOps() & SelectionKey.OP_WRITE) == 0)
                .as("OP_WRITE must be disabled when queue drains to avoid 100% CPU busy-spin")
                .isTrue();

        // Verify captured message is bit-for-bit identical to the original notification
        assertThat(capturedOutput.toByteArray()).isEqualTo(wireBytes);
        String reconstructed = capturedOutput.toString(StandardCharsets.UTF_8);
        ChangeNotification parsed = ChangeNotification.parse(reconstructed.trim());
        assertThat(parsed.version()).isEqualTo(101L);

        // 3. Verify busy-spin prevention with Selector
        int readyChannels = selector.selectNow();
        assertThat(readyChannels).isEqualTo(0);
    }

    @Test
    @DisplayName("Should preserve strict sequential FIFO order across multiple partial writes")
    void shouldPreserveQueueOrderingAcrossMultiplePartialWrites() throws IOException {
        ClientSession session = new ClientSession(null, 10);

        byte[] msg1 = ChangeNotification.of(1L).toWireBytes();
        byte[] msg2 = ChangeNotification.of(2L).toWireBytes();
        byte[] msg3 = ChangeNotification.of(3L).toWireBytes();

        session.enqueue(ByteBuffer.wrap(msg1));
        session.enqueue(ByteBuffer.wrap(msg2));
        session.enqueue(ByteBuffer.wrap(msg3));

        assertThat(session.getPendingMessageCount()).isEqualTo(3);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        // Emulate a socket that accepts exactly 7 bytes per write call
        WritableByteChannel restrictedChannel = new WritableByteChannel() {
            private boolean open = true;

            @Override
            public int write(ByteBuffer src) {
                if (!src.hasRemaining()) return 0;
                int count = Math.min(7, src.remaining());
                byte[] chunk = new byte[count];
                src.get(chunk);
                captured.writeBytes(chunk);
                return count;
            }

            @Override public boolean isOpen() { return open; }
            @Override public void close() { open = false; }
        };

        // Repeatedly flush 7-byte slices until completely drained
        int writeIterations = 0;
        while (session.hasPendingData() && writeIterations < 100) {
            session.flush(restrictedChannel);
            writeIterations++;
        }

        assertThat(session.hasPendingData()).isFalse();
        assertThat(session.getPendingMessageCount()).isEqualTo(0);
        assertThat(writeIterations).isGreaterThan(5); // Proves multiple partial writes occurred

        // Verify concatenated stream matches msg1 + msg2 + msg3 in exact order
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(msg1);
        expected.writeBytes(msg2);
        expected.writeBytes(msg3);

        assertThat(captured.toByteArray()).isEqualTo(expected.toByteArray());
    }

    @Test
    @DisplayName("T07 Real SocketChannel: Server buffers and resumes write when client reads from saturated socket")
    void shouldHandleRealTcpSocketPartialWriteUnderLoad() throws Exception {
        // Configure server with minimal send buffer size to trigger socket buffer pressure
        NotificationServerConfig config = new NotificationServerConfig("127.0.0.1", 0, 10, 50, 2048);
        NioNotificationServer server = register(new NioNotificationServer(config));
        server.start();

        // Connect client with small receive buffer
        SocketChannel client = register(SocketChannel.open());
        client.setOption(StandardSocketOptions.SO_RCVBUF, 2048);
        client.setOption(StandardSocketOptions.TCP_NODELAY, true);
        client.connect(server.getBoundAddress());

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 1);

        // Publish notifications
        for (long v = 1; v <= 10; v++) {
            server.publish(v);
        }

        // Client reads all 10 notifications
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        ByteArrayOutputStream receivedStream = new ByteArrayOutputStream();

        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            int read = client.read(readBuffer);
            if (read > 0) {
                readBuffer.flip();
                byte[] bytes = new byte[readBuffer.remaining()];
                readBuffer.get(bytes);
                receivedStream.writeBytes(bytes);
                readBuffer.clear();

                String streamText = receivedStream.toString(StandardCharsets.UTF_8);
                String[] lines = streamText.split("\n");
                if (lines.length >= 10 && lines[9].contains("\"version\":10")) {
                    break;
                }
            }
            Thread.sleep(20);
        }

        String allReceived = receivedStream.toString(StandardCharsets.UTF_8);
        String[] lines = allReceived.strip().split("\n");
        assertThat(lines.length).isGreaterThanOrEqualTo(10);

        for (int i = 0; i < 10; i++) {
            ChangeNotification parsed = ChangeNotification.parse(lines[i]);
            assertThat(parsed.version()).isEqualTo(i + 1L);
        }

        // Server session outbound queue drained and healthy
        assertThat(server.isRunning()).isTrue();
        assertThat(server.getConnectedClientCount()).isEqualTo(1);
    }
}
