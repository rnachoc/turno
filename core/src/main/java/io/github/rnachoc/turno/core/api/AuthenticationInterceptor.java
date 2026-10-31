package io.github.rnachoc.turno.core.api;

import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.metrics.DomainMetrics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates and authorises every call to a controller.
 *
 * <p>It runs after Spring has resolved the handler and decides on the handler itself, not on the
 * URL text. An encoded or oddly written path that reaches a controller therefore gets the same
 * checks, which a path-prefix filter could miss. It also applies the limits per caller, before the
 * request costs anything.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
class AuthenticationInterceptor implements HandlerInterceptor {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final byte[] agentsDigest;
    private final byte[] staffDigest;
    private final byte[] workflowsDigest;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;
    private final AuditService audit;
    private final DomainMetrics metrics;

    AuthenticationInterceptor(
            SecurityProperties properties,
            RateLimiter limiter,
            RateLimitProperties limits,
            AuditService audit,
            DomainMetrics metrics) {
        this.agentsDigest = sha256(properties.agentsToken());
        this.staffDigest = sha256(properties.staffToken());
        this.workflowsDigest = sha256(properties.workflowsToken());
        this.limiter = limiter;
        this.limits = limits;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        // Checked before the token, or a guess could tell it was right from being let through.
        String address = "address:" + Objects.requireNonNullElse(request.getRemoteAddr(), "unknown");
        if (limiter.hasReached(address, limits.failedAuthenticationsPerMinute())) {
            metrics.rateLimited("unknown");
            throw tooMany(response);
        }
        ApiPrincipal principal = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (principal == null) {
            limiter.record(address);
            metrics.authenticationFailed();
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        request.setAttribute(ApiPrincipal.ATTRIBUTE, principal);
        RateLimiter.Decision decision = limiter.count("principal:" + principal.name(), limits.perMinute(principal));
        if (decision != RateLimiter.Decision.ALLOW) {
            if (decision == RateLimiter.Decision.REFUSE_FIRST) {
                recordFirstRefusal(principal);
            }
            metrics.rateLimited(principal.actor());
            throw tooMany(response);
        }
        boolean staffOnly = method.getBeanType().isAnnotationPresent(StaffOnly.class);
        boolean workflowsOnly = method.getBeanType().isAnnotationPresent(WorkflowsOnly.class);
        if ((staffOnly && principal != ApiPrincipal.STAFF)
                || (workflowsOnly && principal != ApiPrincipal.WORKFLOWS)
                || (!workflowsOnly && principal == ApiPrincipal.WORKFLOWS)) {
            throw new DomainException(ErrorCode.FORBIDDEN);
        }
        return true;
    }

    private DomainException tooMany(HttpServletResponse response) {
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(limiter.secondsUntilNextWindow()));
        return new DomainException(ErrorCode.RATE_LIMITED);
    }

    /** One entry per minute is enough, and a flood must not turn into a flood of database writes. */
    private void recordFirstRefusal(ApiPrincipal principal) {
        try {
            audit.rejected(principal.actor(), "rate-limit", null, null, ErrorCode.RATE_LIMITED.name());
        } catch (RuntimeException e) {
            LOG.error("Could not record that a caller went over its limit", e);
        }
    }

    private @Nullable ApiPrincipal authenticate(@Nullable String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        // Comparing fixed-size digests in constant time reveals neither the token nor its length.
        byte[] presented = sha256(header.substring(BEARER.length()));
        boolean isAgents = MessageDigest.isEqual(presented, agentsDigest);
        boolean isStaff = MessageDigest.isEqual(presented, staffDigest);
        boolean isWorkflows = MessageDigest.isEqual(presented, workflowsDigest);
        if (isStaff) {
            return ApiPrincipal.STAFF;
        }
        if (isWorkflows) {
            return ApiPrincipal.WORKFLOWS;
        }
        return isAgents ? ApiPrincipal.AGENTS : null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
        }
    }
}
