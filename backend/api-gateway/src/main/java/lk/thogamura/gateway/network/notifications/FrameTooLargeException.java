package lk.thogamura.gateway.network.notifications;

import java.io.IOException;

/**
 * Thrown when an inbound notification frame exceeds the maximum allowed length.
 */
public class FrameTooLargeException extends IOException {
    public FrameTooLargeException(String message) {
        super(message);
    }
}
