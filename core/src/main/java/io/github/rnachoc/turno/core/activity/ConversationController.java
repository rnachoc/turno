package io.github.rnachoc.turno.core.activity;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the agents report what they did. The patterns only let codes through, so a bug or a
 * manipulated agent cannot put what a patient wrote into this record.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@RequestMapping("/api/v1/patients/{patientId}/conversations/{conversationId}/steps")
class ConversationController {

    /** A step of a conversation. */
    record StepRequest(
            @NotNull @Pattern(regexp = "[a-z_]{1,30}") String kind,
            @NotNull @Pattern(regexp = "[A-Za-z0-9_.:\\[\\]-]{0,100}") String detail,
            @Min(0) @Max(100_000_000) int inputTokens,
            @Min(0) @Max(100_000_000) int outputTokens,
            @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal estimatedCostUsd,
            @NotNull @DecimalMin("0") @DecimalMax("100000") BigDecimal seconds) {}

    /** Consecutive steps, numbered from the first one's place in the conversation. */
    record StepsRequest(
            @Min(0) @Max(1_000_000) int firstPosition,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid StepRequest> steps) {}

    private final ActivityService activity;
    private final IdempotencyService idempotency;

    ConversationController(ActivityService activity, IdempotencyService idempotency) {
        this.activity = activity;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<String> record(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @PathVariable UUID patientId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody StepsRequest body,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                body,
                HttpStatus.OK,
                () -> Map.of("added", activity.record(principal, patientId, conversationId, steps(body))));
    }

    private static List<AgentStep> steps(StepsRequest body) {
        List<StepRequest> steps = body.steps();
        return IntStream.range(0, steps.size())
                .mapToObj(
                        i -> {
                            StepRequest step = steps.get(i);
                            return new AgentStep(
                                    body.firstPosition() + i,
                                    step.kind(),
                                    step.detail(),
                                    step.inputTokens(),
                                    step.outputTokens(),
                                    step.estimatedCostUsd(),
                                    step.seconds(),
                                    Instant.EPOCH);
                        })
                .toList();
    }
}
