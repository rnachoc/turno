package io.github.rnachoc.turno.core.patient;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.idempotency.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP interface for patients.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@RequestMapping("/api/v1/patients")
class PatientController {

    private static final String PHONE = "^\\+[1-9][0-9]{6,14}$";

    /**
     * Request to register a patient. Email addresses must be on the reserved {@code .example}
     * domain, so that no real person can ever be contacted by mistake. See {@code docs/adr/0004}.
     */
    record RegisterRequest(
            @NotBlank @Size(max = 200) @Pattern(regexp = "^[^\\p{Cntrl}\\p{Cf}\\p{Cs}]+$") String fullName,
            @NotBlank @Pattern(regexp = PHONE) String phone,
            @Nullable @Size(max = 254) @Pattern(regexp = "^[^@\\s\\p{Cntrl}\\p{Cf}\\p{Cs}]+@[^@\\s\\p{Cntrl}\\p{Cf}\\p{Cs}]+\\.example$") String email,
            @NotBlank @Pattern(regexp = "^[a-z]{2}$") String language) {}

    /** Request to find a patient. The phone number travels in the body to keep it out of URLs and logs. */
    record LookupRequest(@NotBlank @Pattern(regexp = PHONE) String phone) {}

    private final PatientService patients;
    private final IdempotencyService idempotency;

    PatientController(PatientService patients, IdempotencyService idempotency) {
        this.patients = patients;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<String> register(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @RequestHeader(name = IdempotencyService.HEADER, required = false) @Nullable String key,
            @Valid @RequestBody RegisterRequest body,
            HttpServletRequest request) {
        return idempotency.execute(
                principal,
                key,
                request,
                body,
                HttpStatus.CREATED,
                () -> patients.register(principal, body.fullName(), body.phone(), body.email(), body.language()));
    }

    @PostMapping("/lookup")
    Patient lookup(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @Valid @RequestBody LookupRequest body) {
        return patients.lookup(principal, body.phone());
    }

    @GetMapping("/{patientId}")
    Patient get(
            @RequestAttribute(ApiPrincipal.ATTRIBUTE) ApiPrincipal principal,
            @PathVariable UUID patientId) {
        return patients.get(principal, patientId);
    }
}
