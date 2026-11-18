package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ClientSessionTest {

    @Test
    @DisplayName("Should bound outbound queue to maxPendingMessages and reject overflow")
    void shouldBoundOutboundQueue() {
        ClientSession session = new ClientSession(null, 3);

        assertThat(session.hasPendingData()).isFalse();
        assertThat(session.getPendingMessageCount()).isEqualTo(0);

        // Enqueue within limit
        assertThat(session.enqueue(ByteBuffer.wrap("msg1\n".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(session.enqueue(ByteBuffer.wrap("msg2\n".getBytes(StandardCharsets.UTF_8)))).isTrue();
        assertThat(session.enqueue(ByteBuffer.wrap("msg3\n".getBytes(StandardCharsets.UTF_8)))).isTrue();

        assertThat(session.getPendingMessageCount()).isEqualTo(3);
        assertThat(session.hasPendingData()).isTrue();

        // 4th enqueue exceeds bound -> slow client detected
        assertThat(session.enqueue(ByteBuffer.wrap("msg4\n".getBytes(StandardCharsets.UTF_8)))).isFalse();
        assertThat(session.getPendingMessageCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should handle partial writes correctly and resume from exact position")
    void shouldHandlePartialWrites() throws IOException {
        ClientSession session = new ClientSession(null, 10);

        String message = "{\"v\":1,\"type\":\"CHANGED\",\"version\":42}\n";
        byte[] originalBytes = message.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.wrap(originalBytes);
        session.enqueue(buffer);

        // A mock WritableByteChannel that writes at most 5 bytes per call
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        WritableByteChannel restrictedChannel = new WritableByteChannel() {
            private boolean open = true;

            @Override
            public int write(ByteBuffer src) {
                if (!src.hasRemaining()) {
                    return 0;
                }
                int bytesToWrite = Math.min(5, src.remaining());
                byte[] temp = new byte[bytesToWrite];
                src.get(temp);
                outputStream.writeBytes(temp);
                return bytesToWrite;
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

        // First flush: writes only 5 bytes
        boolean fullyFlushed = session.flush(restrictedChannel);
        assertThat(fullyFlushed).isFalse();
        assertThat(session.hasPendingData()).isTrue();
        assertThat(session.getPendingMessageCount()).isEqualTo(1);
        assertThat(outputStream.size()).isEqualTo(5);

        // Repeated flushes until drained
        int attempts = 1;
        while (!fullyFlushed && attempts < 20) {
            fullyFlushed = session.flush(restrictedChannel);
            attempts++;
        }

        assertThat(fullyFlushed).isTrue();
        assertThat(session.hasPendingData()).isFalse();
        assertThat(session.getPendingMessageCount()).isEqualTo(0);
        assertThat(outputStream.toByteArray()).isEqualTo(originalBytes);
    }

    @Test
    @DisplayName("Should clear pending queue cleanly")
    void shouldClearQueue() {
        ClientSession session = new ClientSession(null, 5);
        session.enqueue(ByteBuffer.wrap("test\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(session.hasPendingData()).isTrue();

        session.clear();
        assertThat(session.hasPendingData()).isFalse();
        assertThat(session.getPendingMessageCount()).isEqualTo(0);
    }
}
