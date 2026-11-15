package lk.thogamura.protocol;

/**
 * Only a build-time marker. The actual v1 wire codec and validation
 * belong to Member 1 and must match docs/contracts/NETWORK_PROTOCOL_V1.md.
 */
public final class ProtocolInfo {
    public static final int WIRE_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 65_536;

    private ProtocolInfo() {
    }
}
