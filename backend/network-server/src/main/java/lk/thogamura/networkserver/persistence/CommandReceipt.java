package lk.thogamura.networkserver.persistence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable entity representing a durable command receipt in PostgreSQL {@code command_receipts}.
 *
 * <p>Enforces Protocol v1 replay and idempotency guarantees:
 * <ul>
 *   <li>Same {@code commandRequestId} + matching {@code payloadFingerprint} replays {@code resultJson}</li>
 *   <li>Same {@code commandRequestId} + conflicting {@code payloadFingerprint} returns {@code DUPLICATE_ID_CONFLICT}</li>
 * </ul>
 */
public record CommandReceipt(
        UUID commandRequestId,
        String commandType,
        String payloadFingerprint,
        String resultJson,
        Instant createdAt
) {
    public CommandReceipt {
        Objects.requireNonNull(commandRequestId, "commandRequestId must not be null");
        Objects.requireNonNull(commandType, "commandType must not be null");
        Objects.requireNonNull(payloadFingerprint, "payloadFingerprint must not be null");
        Objects.requireNonNull(resultJson, "resultJson must not be null");
        if (payloadFingerprint.length() != 64) {
            throw new IllegalArgumentException(
                    "payloadFingerprint must be 64 characters hex: " + payloadFingerprint
            );
        }
    }

    public static CommandReceipt of(UUID requestId, String commandType, String fingerprint, String resultJson) {
        return new CommandReceipt(requestId, commandType, fingerprint, resultJson, Instant.now());
    }
}
