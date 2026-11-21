package lk.thogamura.networkserver.transport.tcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameDeadlineInputStreamTest {

    @Test
    @DisplayName("Should read complete frame without triggering deadline")
    void shouldReadNormalFrameWithoutDeadlineTimeout() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", server.getLocalPort());
             Socket accepted = server.accept()) {

            FrameDeadlineInputStream stream = new FrameDeadlineInputStream(
                    accepted.getInputStream(), accepted, 2000, 5000
            );

            client.getOutputStream().write("Hello World\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            byte[] buf = new byte[64];
            int read = stream.read(buf, 0, buf.length);

            assertThat(read).isEqualTo(12);
            assertThat(new String(buf, 0, read, StandardCharsets.UTF_8)).isEqualTo("Hello World\n");
            assertThat(stream.isFrameInProgress()).isFalse();
        }
    }

    @Test
    @DisplayName("Should detect multi-frame coalesced stream and reset deadline per frame")
    void shouldHandleCoalescedFramesAndResetDeadline() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", server.getLocalPort());
             Socket accepted = server.accept()) {

            FrameDeadlineInputStream stream = new FrameDeadlineInputStream(
                    accepted.getInputStream(), accepted, 2000, 5000
            );

            client.getOutputStream().write("Frame1\nFrame2\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            byte[] buf = new byte[64];
            int read = stream.read(buf, 0, buf.length);

            assertThat(read).isEqualTo(14);
            assertThat(stream.isFrameInProgress()).isFalse();
        }
    }
}
