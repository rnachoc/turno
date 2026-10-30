package io.github.rnachoc.turno.core.idempotency;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes an operation safe to retry: however many times a request with the same key arrives, even at
 * the same moment, the operation runs once and every caller gets the same response.
 *
 * <p>The key is claimed in the same transaction as the operation. A successful operation commits
 * together with its stored response, and a refused one rolls back together with its key, so a
 * refusal is not remembered and the retry is judged again. See {@code docs/adr/0006}.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class IdempotencyService {

    /** Header that carries the key. */
    public static final String HEADER = "Idempotency-Key";

    /** Response header that tells a retry its response comes from the first execution. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final Pattern VALID_KEY = Pattern.compile("^[A-Za-z0-9_-]{8,128}$");
    private static final Duration RETENTION = Duration.ofDays(7);

    private final IdempotencyRepository repository;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final JsonMapper json;

    IdempotencyService(
            IdempotencyRepository repository, TransactionTemplate transaction, Clock clock, JsonMapper json) {
        this.repository = repository;
        this.transaction = transaction;
        this.clock = clock;
        this.json = json;
    }

    /**
     * Runs an operation once per key.
     *
     * @param principal the caller; keys of different callers never collide
     * @param key the value of the idempotency header
     * @param request the HTTP request, which identifies the operation
     * @param body the request body, if any
     * @param success the status to answer when the operation succeeds
     * @param operation the operation
     * @return the response of the operation, either fresh or replayed
     */
    public ResponseEntity<String> execute(
            ApiPrincipal principal,
            @Nullable String key,
            HttpServletRequest request,
            @Nullable Object body,
            HttpStatus success,
            Supplier<?> operation) {
        requireValid(key);
        String fingerprint = fingerprint(request, body);
        return transaction.execute(
                status -> {
                    if (repository.tryClaim(principal.actor(), key, fingerprint, clock.instant())) {
                        String response = json.writeValueAsString(operation.get());
                        repository.complete(principal.actor(), key, success.value(), response);
                        return respond(success.value(), response, false);
                    }
                    IdempotencyRepository.Stored stored =
                            repository.find(principal.actor(), key).orElseThrow();
                    if (!stored.fingerprint().equals(fingerprint)) {
                        throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    return respond(stored.status(), stored.response(), true);
                });
    }

    /**
     * Deletes keys past their retention period.
     *
     * @return how many keys were deleted
     */
    @Transactional
    public int purgeExpired() {
        return repository.deleteOlderThan(clock.instant().minus(RETENTION));
    }

    private static void requireValid(@Nullable String key) {
        if (key == null) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (!VALID_KEY.matcher(key).matches()) {
            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
    }

    private String fingerprint(HttpServletRequest request, @Nullable Object body) {
        String canonical =
                request.getMethod() + " " + request.getRequestURI() + "\n" + json.writeValueAsString(body);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
        }
    }

    private static ResponseEntity<String> respond(int status, String body, boolean replayed) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, String.valueOf(replayed))
                .body(body);
    }
}
