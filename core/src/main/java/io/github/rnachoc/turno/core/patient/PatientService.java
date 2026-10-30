package io.github.rnachoc.turno.core.patient;

import io.github.rnachoc.turno.core.api.ApiPrincipal;
import io.github.rnachoc.turno.core.api.DomainException;
import io.github.rnachoc.turno.core.api.ErrorCode;
import io.github.rnachoc.turno.core.audit.AuditService;
import io.github.rnachoc.turno.core.tenant.Tenant;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registers patients and finds them. Every access to patient data leaves an entry in the audit
 * trail, without the data itself.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Service
public class PatientService {

    private final PatientRepository repository;
    private final AuditService audit;
    private final Tenant tenant;
    private final Clock clock;

    PatientService(PatientRepository repository, AuditService audit, Tenant tenant, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.tenant = tenant;
        this.clock = clock;
    }

    /**
     * Registers a patient.
     *
     * @param principal the caller
     * @param fullName full name
     * @param phone phone number in E.164 format
     * @param email email address, if known
     * @param language language in which the patient is attended
     * @return the new patient
     */
    @Transactional
    public Patient register(
            ApiPrincipal principal, String fullName, String phone, @Nullable String email, String language) {
        if (!tenant.languages().contains(language)) {
            throw new DomainException(ErrorCode.LANGUAGE_NOT_SUPPORTED);
        }
        Patient patient = new Patient(UUID.randomUUID(), fullName, phone, email, language, clock.instant());
        if (!repository.insertIfPhoneIsFree(patient)) {
            throw new DomainException(ErrorCode.PATIENT_ALREADY_EXISTS);
        }
        audit.accepted(principal.actor(), "patient.register", patient.id().toString(), patient.id(), Map.of());
        return patient;
    }

    /**
     * Reads a patient by identifier.
     *
     * @param principal the caller
     * @param id the patient
     * @return the patient
     */
    @Transactional
    public Patient get(ApiPrincipal principal, UUID id) {
        Patient patient = repository.findById(id).orElseThrow(PatientService::notFound);
        audit.accepted(principal.actor(), "patient.read", id.toString(), id, Map.of());
        return patient;
    }

    /**
     * Finds a patient by exact phone number.
     *
     * @param principal the caller
     * @param phone the phone number
     * @return the patient
     */
    @Transactional
    public Patient lookup(ApiPrincipal principal, String phone) {
        Patient patient = repository.findByPhone(phone).orElseThrow(PatientService::notFound);
        audit.accepted(principal.actor(), "patient.lookup", patient.id().toString(), patient.id(), Map.of());
        return patient;
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.PATIENT_NOT_FOUND);
    }
}
