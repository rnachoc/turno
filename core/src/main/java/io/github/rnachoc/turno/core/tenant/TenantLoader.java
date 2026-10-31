package io.github.rnachoc.turno.core.tenant;

import io.github.rnachoc.turno.core.tenant.Tenant.Practitioner;
import io.github.rnachoc.turno.core.tenant.Tenant.TimeWindow;
import io.github.rnachoc.turno.core.tenant.Tenant.Treatment;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a tenant file and checks the rules that its JSON Schema cannot express, such as references
 * between sections. See {@code docs/adr/0005}.
 *
 * <p>SnakeYAML parses the file because Jackson's YAML support does not expand aliases. Its safe
 * constructor only ever builds plain maps and lists.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public final class TenantLoader {

    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                    .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .build();

    private TenantLoader() {}

    /**
     * Loads and validates a tenant file.
     *
     * @param file path of the YAML file
     * @return the validated tenant
     * @throws InvalidTenantException if the file cannot be read or breaks any rule
     */
    public static Tenant load(Path file) {
        Tenant tenant;
        try (Reader reader = Files.newBufferedReader(file)) {
            Object raw = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
            tenant = MAPPER.convertValue(raw, Tenant.class);
        } catch (IOException | YAMLException | JacksonException | IllegalArgumentException e) {
            throw new InvalidTenantException(file.toString(), List.of(String.valueOf(e.getMessage())));
        }
        List<String> problems = problemsIn(tenant);
        if (!problems.isEmpty()) {
            throw new InvalidTenantException(file.toString(), problems);
        }
        return tenant;
    }

    /**
     * Checks a tenant against the rules that span several sections.
     *
     * @param tenant the tenant to check
     * @return a description of each problem found; empty if the tenant is valid
     */
    static List<String> problemsIn(Tenant tenant) {
        List<String> problems = new ArrayList<>();
        if (!tenant.fictional()) {
            problems.add("fictional must be true: Turno only runs on synthetic data");
        }
        if (!tenant.contactEmail().endsWith(".example")) {
            problems.add("contact_email must use the reserved .example domain");
        }
        checkCurrency(tenant, problems);
        checkOpeningHours(tenant, problems);
        checkTreatments(tenant, problems);
        checkPractitioners(tenant, problems);
        checkPolicies(tenant, problems);
        return problems;
    }

    private static void checkCurrency(Tenant tenant, List<String> problems) {
        try {
            Currency.getInstance(tenant.currency());
        } catch (IllegalArgumentException e) {
            problems.add("unknown currency " + tenant.currency());
        }
    }

    private static void checkOpeningHours(Tenant tenant, List<String> problems) {
        for (DayOfWeek day : DayOfWeek.values()) {
            List<TimeWindow> windows = tenant.openingHours().get(day);
            if (windows == null) {
                problems.add("opening_hours is missing " + day);
                continue;
            }
            LocalTime previousClose = LocalTime.MIDNIGHT;
            for (TimeWindow window : windows) {
                if (!window.open().isBefore(window.close())) {
                    problems.add(day + ": a window must open before it closes");
                }
                if (window.open().isBefore(previousClose)) {
                    problems.add(day + ": windows must be in order and must not overlap");
                }
                previousClose = window.close();
            }
        }
    }

    private static void checkTreatments(Tenant tenant, List<String> problems) {
        Set<String> ids = new HashSet<>();
        for (Treatment treatment : tenant.treatments()) {
            if (!ids.add(treatment.id())) {
                problems.add("duplicate treatment " + treatment.id());
            }
            if (treatment.durationMinutes() <= 0) {
                problems.add(treatment.id() + ": duration must be positive");
            }
            if (treatment.priceCents() < 0) {
                problems.add(treatment.id() + ": price must not be negative");
            }
            if (treatment.depositCents() < 0 || treatment.depositCents() > treatment.priceCents()) {
                problems.add(treatment.id() + ": deposit must be between 0 and the price");
            }
            if (!treatment.name().keySet().containsAll(tenant.languages())) {
                problems.add(treatment.id() + ": needs a name in every language of the clinic");
            }
            if (tenant.practitioners().stream()
                    .noneMatch(p -> p.treatments().contains(treatment.id()))) {
                problems.add(treatment.id() + ": no practitioner performs it");
            }
        }
    }

    private static void checkPractitioners(Tenant tenant, List<String> problems) {
        Set<String> ids = new HashSet<>();
        for (Practitioner practitioner : tenant.practitioners()) {
            if (!ids.add(practitioner.id())) {
                problems.add("duplicate practitioner " + practitioner.id());
            }
            for (String treatmentId : practitioner.treatments()) {
                if (tenant.treatment(treatmentId).isEmpty()) {
                    problems.add(practitioner.id() + ": unknown treatment " + treatmentId);
                }
            }
            for (DayOfWeek day : practitioner.workingDays()) {
                List<TimeWindow> windows = tenant.openingHours().get(day);
                if (windows == null || windows.isEmpty()) {
                    problems.add(practitioner.id() + ": works on " + day + ", when it is closed");
                }
            }
        }
    }

    private static void checkPolicies(Tenant tenant, List<String> problems) {
        Tenant.BookingPolicy booking = tenant.booking();
        if (booking.slotGranularityMinutes() <= 0) {
            problems.add("slot_granularity_minutes must be positive");
        }
        if (booking.maxAdvanceDays() <= 0) {
            problems.add("max_advance_days must be positive");
        }
        if (booking.holdTtlMinutes() <= 0) {
            problems.add("hold_ttl_minutes must be positive");
        }
        if (booking.maxActiveHoldsPerPatient() <= 0) {
            problems.add("max_active_holds_per_patient must be positive");
        }
        // Otherwise a hold could still be confirmable after its appointment has started.
        if (booking.holdTtlMinutes() > booking.minNoticeMinutes()) {
            problems.add("hold_ttl_minutes must not exceed min_notice_minutes");
        }
        if (tenant.cancellation().freeUntilHoursBefore() < 0) {
            problems.add("free_until_hours_before must not be negative");
        }
        if (!tenant.outreach().allowedFrom().isBefore(tenant.outreach().allowedUntil())) {
            problems.add("outreach allowed_from must be before allowed_until");
        }
        if (tenant.waitlist().offerTtlMinutes() <= 0) {
            problems.add("offer_ttl_minutes must be positive");
        }
        if (tenant.waitlist().maxOffersPerSlot() <= 0) {
            problems.add("max_offers_per_slot must be positive");
        }
    }
}
