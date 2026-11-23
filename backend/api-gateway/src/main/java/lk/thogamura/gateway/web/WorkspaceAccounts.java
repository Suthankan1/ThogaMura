package lk.thogamura.gateway.web;

import lk.thogamura.protocol.access.WorkspaceAccess;
import lk.thogamura.protocol.access.WorkspaceAccess.Actor;
import lk.thogamura.protocol.access.WorkspaceAccess.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "thogamura.access.enabled", havingValue = "true")
final class WorkspaceAccounts {
    private final Map<String, Account> accounts;
    private final Account unknown;
    private final Semaphore verificationSlots = new Semaphore(4);

    WorkspaceAccounts(@Value("${THOGAMURA_ACCESS_USERS:}") String configured) {
        Map<String, Account> parsed = new HashMap<>();
        try {
            for (String setting : configured.split(";", -1)) {
                String[] fields = setting.split(",", -1);
                if (fields.length != 5 || parsed.size() >= 100) throw new IllegalArgumentException();
                Actor actor = new Actor(fields[0], Role.valueOf(fields[1]), fields[2]);
                byte[] salt = HexFormat.of().parseHex(fields[3]);
                byte[] hash = HexFormat.of().parseHex(fields[4]);
                if (salt.length != 16 || hash.length != 32 ||
                        parsed.putIfAbsent(actor.name(), new Account(actor, salt, hash)) != null) {
                    throw new IllegalArgumentException();
                }
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Configure unique workspace users with role, branch, 16-byte salt and 32-byte hash");
        }
        accounts = Map.copyOf(parsed);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        unknown = new Account(null, salt, new byte[32]);
    }

    Actor authenticate(String login, String password) {
        if (!WorkspaceAccess.isIdentifier(login) || password == null || password.isEmpty() || password.length() > 128) return null;
        Account account = accounts.getOrDefault(login, unknown);
        if (!verificationSlots.tryAcquire()) return null;
        try {
            if (!account.allowAttempt()) return null;
            char[] characters = password.toCharArray();
            PBEKeySpec spec = new PBEKeySpec(characters, account.salt, 600_000, 256);
            Arrays.fill(characters, '\0');
            try {
                byte[] actual = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                boolean matches = MessageDigest.isEqual(account.hash, actual);
                Arrays.fill(actual, (byte) 0);
                return matches ? account.actor : null;
            } catch (GeneralSecurityException failure) {
                throw new IllegalStateException("Workspace password verification is unavailable", failure);
            } finally {
                spec.clearPassword();
            }
        } finally {
            verificationSlots.release();
        }
    }

    private static final class Account {
        private final Actor actor;
        private final byte[] salt;
        private final byte[] hash;
        private long resetAt = System.nanoTime();
        private int attempts;

        private Account(Actor actor, byte[] salt, byte[] hash) {
            this.actor = actor;
            this.salt = salt;
            this.hash = hash;
        }

        private synchronized boolean allowAttempt() {
            long now = System.nanoTime();
            if (now - resetAt >= 0) {
                attempts = 0;
                resetAt = now + TimeUnit.MINUTES.toNanos(1);
            }
            if (attempts >= 10) return false;
            attempts++;
            return true;
        }
    }
}
