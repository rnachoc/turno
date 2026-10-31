package io.github.rnachoc.turno.core.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates and authorises every call to a controller.
 *
 * <p>It runs after Spring has resolved the handler and decides on the handler itself, not on the
 * URL text. An encoded or oddly written path that reaches a controller therefore gets the same
 * checks, which a path-prefix filter could miss.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
class AuthenticationInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    private final byte[] agentsDigest;
    private final byte[] staffDigest;
    private final byte[] workflowsDigest;

    AuthenticationInterceptor(SecurityProperties properties) {
        this.agentsDigest = sha256(properties.agentsToken());
        this.staffDigest = sha256(properties.staffToken());
        this.workflowsDigest = sha256(properties.workflowsToken());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        ApiPrincipal principal = authenticate(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (principal == null) {
            throw new DomainException(ErrorCode.UNAUTHENTICATED);
        }
        request.setAttribute(ApiPrincipal.ATTRIBUTE, principal);
        boolean staffOnly = method.getBeanType().isAnnotationPresent(StaffOnly.class);
        boolean workflowsOnly = method.getBeanType().isAnnotationPresent(WorkflowsOnly.class);
        if ((staffOnly && principal != ApiPrincipal.STAFF)
                || (workflowsOnly && principal != ApiPrincipal.WORKFLOWS)
                || (!workflowsOnly && principal == ApiPrincipal.WORKFLOWS)) {
            throw new DomainException(ErrorCode.FORBIDDEN);
        }
        return true;
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
