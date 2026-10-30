package io.github.rnachoc.turno.core.availability;

import io.github.rnachoc.turno.core.tenant.Tenant;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP interface for what the clinic offers and when it is free.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@RestController
@RequestMapping("/api/v1")
class AvailabilityController {

    private final AvailabilityService availability;
    private final Tenant tenant;

    AvailabilityController(AvailabilityService availability, Tenant tenant) {
        this.availability = availability;
        this.tenant = tenant;
    }

    @GetMapping("/clinic")
    Tenant clinic() {
        return tenant;
    }

    @GetMapping("/availability")
    List<Slot> availability(
            @RequestParam String treatmentId,
            @RequestParam(required = false) @Nullable String practitionerId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return availability.freeSlots(treatmentId, practitionerId, from, to);
    }
}
