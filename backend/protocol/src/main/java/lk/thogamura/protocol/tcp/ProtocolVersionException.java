package lk.thogamura.protocol.tcp;

import lk.thogamura.protocol.ProtocolInfo;

/**
 * Thrown when an incoming frame specifies an unsupported or incompatible protocol version {@code v}.
 */
public class ProtocolVersionException extends MalformedFrameException {

    private final int unsupportedVersion;

    public ProtocolVersionException(String message, int unsupportedVersion) {
        this(message, unsupportedVersion, null);
    }

    public ProtocolVersionException(String message, int unsupportedVersion, String requestId) {
        super(message, ProtocolInfo.ERROR_UNSUPPORTED_VERSION, requestId);
        this.unsupportedVersion = unsupportedVersion;
    }

    public int getUnsupportedVersion() {
        return unsupportedVersion;
    }
}
