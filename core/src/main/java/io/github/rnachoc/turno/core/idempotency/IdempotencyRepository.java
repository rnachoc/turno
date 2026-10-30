package io.github.rnachoc.turno.core.idempotency;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Stores the responses of completed operations, per caller and key.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Repository
class IdempotencyRepository {

    /** A completed operation, as stored. */
    record Stored(String fingerprint, int status, String response) {}

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims a key. If another transaction holds the same key, this blocks until it finishes: after
     * a commit the key is taken, and after a rollback this call claims it.
     *
     * @return whether this call claimed the key
     */
    boolean tryClaim(String principal, String key, String fingerprint, Instant now) {
        return jdbc.sql(
                                """
                                INSERT INTO idempotency_key (principal, key, fingerprint, created_at)
                                VALUES (:principal, :key, :fingerprint, :now)
                                ON CONFLICT (principal, key) DO NOTHING
                                """)
                        .param("principal", principal)
                        .param("key", key)
                        .param("fingerprint", fingerprint)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    void complete(String principal, String key, int status, String response) {
        jdbc.sql(
                        """
                        UPDATE idempotency_key SET status = :status, response = :response
                        WHERE principal = :principal AND key = :key
                        """)
                .param("status", status)
                .param("response", response)
                .param("principal", principal)
                .param("key", key)
                .update();
    }

    Optional<Stored> find(String principal, String key) {
        return jdbc.sql(
                        """
                        SELECT fingerprint, status, response FROM idempotency_key
                        WHERE principal = :principal AND key = :key
                        """)
                .param("principal", principal)
                .param("key", key)
                .query(
                        (rs, row) ->
                                new Stored(
                                        rs.getString("fingerprint"),
                                        rs.getInt("status"),
                                        rs.getString("response")))
                .optional();
    }

    int deleteOlderThan(Instant limit) {
        return jdbc.sql("DELETE FROM idempotency_key WHERE created_at < :limit")
                .param("limit", OffsetDateTime.ofInstant(limit, ZoneOffset.UTC))
                .update();
    }
}
