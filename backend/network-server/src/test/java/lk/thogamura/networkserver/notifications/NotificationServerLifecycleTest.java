package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class NotificationServerLifecycleTest {

    @Test
    @DisplayName("Should start and stop server via lifecycle methods on ephemeral port")
    void shouldStartAndStopViaLifecycle() throws IOException {
        NotificationServerLifecycle lifecycle = new NotificationServerLifecycle("127.0.0.1", 0, 50, 20);

        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        int port = lifecycle.getServer().getPort();
        assertThat(port).isGreaterThan(0);

        // Connect a subscriber through the started lifecycle bean
        try (Socket socket = new Socket("127.0.0.1", port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

            await().atMost(Duration.ofSeconds(3))
                    .until(() -> lifecycle.getServer().getConnectedClientCount() == 1);

            lifecycle.publish(99L);

            String received = reader.readLine();
            assertThat(received).isEqualTo("{\"v\":1,\"type\":\"CHANGED\",\"version\":99}");
        }

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();

        assertThatThrownBy(() -> lifecycle.publish(100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }
}
