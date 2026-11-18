package lk.thogamura.gateway.network.notifications;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationFrameDecoderTest {

    @Test
    @DisplayName("Should decode a single complete frame")
    void shouldDecodeSingleCompleteFrame() throws Exception {
        NotificationFrameDecoder decoder = new NotificationFrameDecoder(1024);
        List<String> frames = new ArrayList<>();

        String message = "{\"v\":1,\"type\":\"CHANGED\",\"version\":42}\n";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8));

        decoder.decode(buffer, frames::add);

        assertThat(frames).containsExactly("{\"v\":1,\"type\":\"CHANGED\",\"version\":42}");
        assertThat(decoder.getAccumulatedBytes()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reconstruct fragmented frame split across multiple reads")
    void shouldReconstructFragmentedFrame() throws Exception {
        NotificationFrameDecoder decoder = new NotificationFrameDecoder(1024);
        List<String> frames = new ArrayList<>();

        // Chunk 1: first half
        String chunk1 = "{\"v\":1,\"type\":\"CH";
        ByteBuffer buf1 = ByteBuffer.wrap(chunk1.getBytes(StandardCharsets.UTF_8));
        decoder.decode(buf1, frames::add);

        assertThat(frames).isEmpty();
        assertThat(decoder.getAccumulatedBytes()).isEqualTo(chunk1.length());

        // Chunk 2: second half with newline
        String chunk2 = "ANGED\",\"version\":99}\n";
        ByteBuffer buf2 = ByteBuffer.wrap(chunk2.getBytes(StandardCharsets.UTF_8));
        decoder.decode(buf2, frames::add);

        assertThat(frames).containsExactly("{\"v\":1,\"type\":\"CHANGED\",\"version\":99}");
        assertThat(decoder.getAccumulatedBytes()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should emit multiple frames received in a single buffer")
    void shouldDecodeMultipleFramesInSingleRead() throws Exception {
        NotificationFrameDecoder decoder = new NotificationFrameDecoder(1024);
        List<String> frames = new ArrayList<>();

        String input = "{\"v\":1,\"type\":\"CHANGED\",\"version\":1}\n"
                + "{\"v\":1,\"type\":\"CHANGED\",\"version\":2}\r\n"
                + "{\"v\":1,\"type\":\"CHANGED\",\"version\":3}\n";
        ByteBuffer buffer = ByteBuffer.wrap(input.getBytes(StandardCharsets.UTF_8));

        decoder.decode(buffer, frames::add);

        assertThat(frames).containsExactly(
                "{\"v\":1,\"type\":\"CHANGED\",\"version\":1}",
                "{\"v\":1,\"type\":\"CHANGED\",\"version\":2}",
                "{\"v\":1,\"type\":\"CHANGED\",\"version\":3}"
        );
        assertThat(decoder.getAccumulatedBytes()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should ignore empty lines and whitespace")
    void shouldIgnoreEmptyLines() throws Exception {
        NotificationFrameDecoder decoder = new NotificationFrameDecoder(1024);
        List<String> frames = new ArrayList<>();

        String input = "\n\n   \n{\"v\":1,\"type\":\"CHANGED\",\"version\":7}\n\n";
        ByteBuffer buffer = ByteBuffer.wrap(input.getBytes(StandardCharsets.UTF_8));

        decoder.decode(buffer, frames::add);

        assertThat(frames).containsExactly("{\"v\":1,\"type\":\"CHANGED\",\"version\":7}");
    }

    @Test
    @DisplayName("Should throw FrameTooLargeException when frame exceeds maxFrameBytes")
    void shouldRejectOversizedFrames() {
        NotificationFrameDecoder decoder = new NotificationFrameDecoder(50);
        List<String> frames = new ArrayList<>();

        String oversizedInput = "A".repeat(100);
        ByteBuffer buffer = ByteBuffer.wrap(oversizedInput.getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoder.decode(buffer, frames::add))
                .isInstanceOf(FrameTooLargeException.class)
                .hasMessageContaining("Frame size exceeded limit");

        // Internal accumulator should be reset
        assertThat(decoder.getAccumulatedBytes()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should reset accumulator state cleanly")
    void shouldResetAccumulator() throws Exception {
        NotificationFrameDecoder decoder = new NotificationFrameDecoder(1024);
        List<String> frames = new ArrayList<>();

        String partial = "{\"v\":1,";
        decoder.decode(ByteBuffer.wrap(partial.getBytes(StandardCharsets.UTF_8)), frames::add);
        assertThat(decoder.getAccumulatedBytes()).isGreaterThan(0);

        decoder.reset();
        assertThat(decoder.getAccumulatedBytes()).isEqualTo(0);
    }
}
