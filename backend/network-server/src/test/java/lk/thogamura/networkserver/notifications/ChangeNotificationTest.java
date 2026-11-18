package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeNotificationTest {

    @Test
    @DisplayName("Should serialize valid notification to strict newline-delimited wire JSON")
    void shouldSerializeToWireJson() {
        ChangeNotification notification = ChangeNotification.of(42);

        assertThat(notification.v()).isEqualTo(1);
        assertThat(notification.type()).isEqualTo("CHANGED");
        assertThat(notification.version()).isEqualTo(42);

        String json = notification.toWireJson();
        assertThat(json).isEqualTo("{\"v\":1,\"type\":\"CHANGED\",\"version\":42}\n");

        byte[] bytes = notification.toWireBytes();
        assertThat(new String(bytes)).isEqualTo("{\"v\":1,\"type\":\"CHANGED\",\"version\":42}\n");
    }

    @Test
    @DisplayName("Should parse valid wire JSON correctly")
    void shouldParseValidWireJson() {
        String wireJson = "{\"v\":1,\"type\":\"CHANGED\",\"version\":100}\n";
        ChangeNotification parsed = ChangeNotification.parse(wireJson);

        assertThat(parsed.v()).isEqualTo(1);
        assertThat(parsed.type()).isEqualTo("CHANGED");
        assertThat(parsed.version()).isEqualTo(100);
    }

    @Test
    @DisplayName("Should reject protocol version other than 1")
    void shouldRejectInvalidProtocolVersion() {
        assertThatThrownBy(() -> new ChangeNotification(2, "CHANGED", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported protocol version");
    }

    @Test
    @DisplayName("Should reject notification type other than CHANGED")
    void shouldRejectInvalidType() {
        assertThatThrownBy(() -> new ChangeNotification(1, "UPDATE", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported notification type");
    }

    @Test
    @DisplayName("Should reject negative version numbers")
    void shouldRejectNegativeVersion() {
        assertThatThrownBy(() -> ChangeNotification.of(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Version must be non-negative");
    }

    @Test
    @DisplayName("Security: parse should strictly reject frames containing sensitive or unexpected business fields")
    void shouldRejectFramesWithExtraFields() {
        // Attack scenario: someone attempts to leak SKU or stock quantity over the unencrypted NIO feed
        String leakedFrame = "{\"v\":1,\"type\":\"CHANGED\",\"version\":42,\"sku\":\"ITEM-001\",\"quantity\":10}";

        assertThatThrownBy(() -> ChangeNotification.parse(leakedFrame))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid fields in notification");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json", "{\"v\":1}", "{\"v\":1,\"type\":\"CHANGED\"}"})
    @DisplayName("Should reject malformed or incomplete frames")
    void shouldRejectMalformedFrames(String invalidInput) {
        assertThatThrownBy(() -> ChangeNotification.parse(invalidInput))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
