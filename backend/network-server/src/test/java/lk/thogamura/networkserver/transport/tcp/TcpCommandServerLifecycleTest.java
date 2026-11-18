package lk.thogamura.networkserver.transport.tcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TcpCommandServerLifecycleTest {

    @Test
    @DisplayName("Lifecycle should start, report status, and stop server cleanly")
    void shouldManageServerLifecycleCleanly() {
        TcpCommandServerConfig config = new TcpCommandServerConfig("127.0.0.1", 0);
        TcpCommandServerLifecycle lifecycle = new TcpCommandServerLifecycle(config, new DefaultCommandDispatcher());

        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(lifecycle.getPort()).isGreaterThan(0);
        assertThat(lifecycle.getServer()).isNotNull();

        // Idempotent start
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();

        // Idempotent stop
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
