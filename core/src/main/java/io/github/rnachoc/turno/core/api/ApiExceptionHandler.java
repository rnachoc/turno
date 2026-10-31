package io.github.rnachoc.turno.core.api;

import io.github.rnachoc.turno.core.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into an RFC 9457 problem with a stable code and a fixed message.
 *
 * <p>Responses never echo request data or internal details, which keeps stack traces, SQL and other
 * patients' data away from a model that might repeat them. Refusals by a business rule also reach
 * the audit trail, as they are the evidence of a manipulated agent.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler implements ResponseBodyAdvice<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AuditService audit;

    ApiExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomain(DomainException e, HttpServletRequest request) {
        // Neither is recorded per request, because anyone could fill the trail with them.
        if (e.code() != ErrorCode.UNAUTHENTICATED && e.code() != ErrorCode.RATE_LIMITED) {
            recordRefusal(e.code(), request);
        }
        HttpHeaders headers = new HttpHeaders();
        if (e.code() == ErrorCode.UNAUTHENTICATED) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return ResponseEntity.status(e.code().status()).headers(headers).body(problem(e.code()));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleLockFailure(PessimisticLockingFailureException e) {
        LOG.info("Concurrent operation was aborted by the database: {}", e.getClass().getSimpleName());
        ErrorCode code = ErrorCode.CONCURRENT_CONFLICT;
        return ResponseEntity.status(code.status()).body(problem(code));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        LOG.error("Unexpected failure in {} {}", request.getMethod(), request.getRequestURI(), e);
        ErrorCode code = ErrorCode.INTERNAL_ERROR;
        return ResponseEntity.status(code.status()).body(problem(code));
    }

    /** Lists which fields failed, but never their values. */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail body = problem(ErrorCode.VALIDATION_FAILED);
        List<String> fields =
                e.getBindingResult().getFieldErrors().stream()
                        .map(error -> error.getField())
                        .distinct()
                        .sorted()
                        .toList();
        body.setProperty("fields", fields);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).headers(headers).body(body);
    }

    /** Replaces Spring's default messages, which can repeat request data, with fixed ones. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception e,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ErrorCode code =
                switch (status.value()) {
                    case 400 -> ErrorCode.VALIDATION_FAILED;
                    case 404 -> ErrorCode.NOT_FOUND;
                    default -> null;
                };
        ProblemDetail problem = ProblemDetail.forStatus(status);
        if (code != null) {
            problem.setDetail(code.message());
            problem.setProperty("code", code.name());
        }
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /** Spring fills in the address that was asked for, and no error repeats what a caller sent. */
    @Override
    public @Nullable Object beforeBodyWrite(
            @Nullable Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) {
            problem.setInstance(null);
        }
        return body;
    }

    private static ProblemDetail problem(ErrorCode code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), code.message());
        problem.setProperty("code", code.name());
        return problem;
    }

    /** Failing to record a refusal must not hide the refusal itself from the caller. */
    private void recordRefusal(ErrorCode code, HttpServletRequest request) {
        try {
            Object principal = request.getAttribute(ApiPrincipal.ATTRIBUTE);
            String actor = principal instanceof ApiPrincipal known ? known.actor() : "unknown";
            String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
            String action = request.getMethod() + " " + route;
            audit.rejected(actor, action, pathVariable(request, "appointmentId"), patientOf(request), code.name());
        } catch (RuntimeException e) {
            LOG.error("Could not record a refused request in the audit trail", e);
        }
    }

    private static @Nullable UUID patientOf(HttpServletRequest request) {
        String value = pathVariable(request, "patientId");
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable String pathVariable(HttpServletRequest request, String name) {
        if (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) instanceof Map<?, ?> variables
                && variables.get(name) instanceof String value) {
            return value;
        }
        return null;
    }
}
