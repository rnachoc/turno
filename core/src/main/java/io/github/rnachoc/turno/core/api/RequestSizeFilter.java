package io.github.rnachoc.turno.core.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses oversized requests before anything reads them, since every request of this API fits well
 * within the limit. A request of unknown length is refused too, because it could otherwise send a
 * body of any size: the limit could only be enforced after buffering it.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestSizeFilter extends OncePerRequestFilter {

    /** Largest body accepted, in bytes. */
    static final long MAX_BODY_BYTES = 64 * 1024;

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long length = request.getContentLengthLong();
        if (METHODS_WITH_BODY.contains(request.getMethod()) && length < 0) {
            refuse(response, HttpStatus.LENGTH_REQUIRED, "LENGTH_REQUIRED");
        } else if (length > MAX_BODY_BYTES) {
            refuse(response, HttpStatus.CONTENT_TOO_LARGE, "REQUEST_TOO_LARGE");
        } else {
            chain.doFilter(request, response);
        }
    }

    private static void refuse(HttpServletResponse response, HttpStatus status, String code)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter()
                .write("{\"status\":%d,\"code\":\"%s\"}".formatted(status.value(), code));
    }
}
