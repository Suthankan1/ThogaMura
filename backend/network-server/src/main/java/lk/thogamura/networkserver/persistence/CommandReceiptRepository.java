package lk.thogamura.networkserver.persistence;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for managing durable command receipts in PostgreSQL.
 */
public interface CommandReceiptRepository {

    /**
     * Persists a durable command receipt atomically within the transaction.
     *
     * @param receipt command receipt to persist
     */
    void insert(CommandReceipt receipt);

    /**
     * Retrieves an existing command receipt by its unique command request ID.
     *
     * @param commandRequestId request UUID
     * @return optional containing the command receipt if found
     */
    Optional<CommandReceipt> findByCommandRequestId(UUID commandRequestId);
}
