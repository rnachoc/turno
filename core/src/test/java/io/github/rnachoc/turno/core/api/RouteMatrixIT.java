package io.github.rnachoc.turno.core.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.support.ApiIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every route of the application against every kind of caller. The routes come from Spring and the
 * table is written by hand, so a new route fails here until someone decides who may use it.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class RouteMatrixIT extends ApiIntegrationTest {

    /** The agents act for patients, and the clinic's staff can do the same. */
    private static final Set<String> FOR_PATIENTS = Set.of(AGENTS, STAFF);

    private static final Set<String> STAFF_ONLY = Set.of(STAFF);
    private static final Set<String> WORKER_ONLY = Set.of(WORKFLOWS);
    private static final String PATIENT = "/api/v1/patients/{patientId}";
    private static final String APPOINTMENT = PATIENT + "/appointments/{appointmentId}";

    private static final Map<String, Set<String>> ACCESS =
            Map.ofEntries(
                    Map.entry("GET /api/v1/clinic", FOR_PATIENTS),
                    Map.entry("GET /api/v1/availability", FOR_PATIENTS),
                    Map.entry("POST /api/v1/patients", FOR_PATIENTS),
                    Map.entry("POST /api/v1/patients/lookup", FOR_PATIENTS),
                    Map.entry("POST /api/v1/patients/lookup-email", FOR_PATIENTS),
                    Map.entry("GET " + PATIENT, FOR_PATIENTS),
                    Map.entry("POST " + PATIENT + "/appointments", FOR_PATIENTS),
                    Map.entry("GET " + PATIENT + "/appointments", FOR_PATIENTS),
                    Map.entry("GET " + APPOINTMENT, FOR_PATIENTS),
                    Map.entry("POST " + APPOINTMENT + "/confirm", FOR_PATIENTS),
                    Map.entry("POST " + APPOINTMENT + "/release", FOR_PATIENTS),
                    Map.entry("POST " + APPOINTMENT + "/cancel", FOR_PATIENTS),
                    Map.entry("POST " + APPOINTMENT + "/reschedule", FOR_PATIENTS),
                    Map.entry("POST " + PATIENT + "/waitlist", FOR_PATIENTS),
                    Map.entry("GET " + PATIENT + "/waitlist", FOR_PATIENTS),
                    Map.entry("POST " + PATIENT + "/waitlist/{entryId}/leave", FOR_PATIENTS),
                    Map.entry("POST " + PATIENT + "/conversations/{conversationId}/steps", FOR_PATIENTS),
                    Map.entry("GET /api/v1/notifications/pending", FOR_PATIENTS),
                    Map.entry("POST /api/v1/notifications/{notificationId}/sent", FOR_PATIENTS),
                    Map.entry("GET /api/v1/staff/audit", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/agenda", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/agenda/range", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/reports/attendance", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/reports/daily", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/appointments/awaiting-outcome", STAFF_ONLY),
                    Map.entry("POST /api/v1/staff/appointments/{appointmentId}/complete", STAFF_ONLY),
                    Map.entry("POST /api/v1/staff/appointments/{appointmentId}/no-show", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/handovers", STAFF_ONLY),
                    Map.entry("POST /api/v1/staff/handovers/{handoverId}/resolve", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/conversations", STAFF_ONLY),
                    Map.entry("GET /api/v1/staff/conversations/{conversationId}", STAFF_ONLY),
                    Map.entry("GET /api/v1/workflows/freed-slots", WORKER_ONLY),
                    Map.entry("POST /api/v1/workflows/freed-slots/{slotId}/acknowledge", WORKER_ONLY),
                    Map.entry("POST /api/v1/workflows/freed-slots/{slotId}/next-offer", WORKER_ONLY),
                    Map.entry("GET /api/v1/workflows/offers/{offerId}", WORKER_ONLY));

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private Set<String> realRoutes() {
        return mappings.getHandlerMethods().keySet().stream()
                .flatMap(
                        info ->
                                info.getMethodsCondition().getMethods().stream()
                                        .flatMap(
                                                method ->
                                                        info.getPatternValues().stream()
                                                                .map(pattern -> method + " " + pattern)))
                .filter(route -> route.contains("/api/"))
                // ApiTestConfiguration adds this one to provoke failures, and it does not exist outside tests.
                .filter(route -> !route.contains("/api/v1/test/"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Response call(String route, String token) {
        String method = route.substring(0, route.indexOf(' '));
        String path =
                route.substring(route.indexOf(' ') + 1).replaceAll("\\{[^}]+\\}", UUID.randomUUID().toString());
        return "POST".equals(method)
                ? send(method, path, token, newKey(), "{}")
                : send(method, path, token, null, null);
    }

    @Test
    void everyRouteHasBeenDecided() {
        assertThat(realRoutes()).containsExactlyInAnyOrderElementsOf(ACCESS.keySet());
    }

    @Test
    void nobodyGetsThroughWithoutARightToken() {
        for (String route : ACCESS.keySet()) {
            for (String token : new String[] {null, "", "wrong-token", AGENTS + "x", AGENTS.toUpperCase()}) {
                assertThat(call(route, token).status()).as("%s with %s", route, token).isEqualTo(401);
            }
        }
    }

    @Test
    void eachCallerReachesWhatItMayAndNothingElse() {
        for (Map.Entry<String, Set<String>> entry : ACCESS.entrySet()) {
            for (String token : List.of(AGENTS, STAFF, WORKFLOWS)) {
                int status = call(entry.getKey(), token).status();
                if (entry.getValue().contains(token)) {
                    assertThat(status).as("%s with %s", entry.getKey(), token).isNotIn(401, 403);
                } else {
                    assertThat(status).as("%s with %s", entry.getKey(), token).isEqualTo(403);
                }
            }
        }
    }

    @Test
    void aForbiddenCallChangesNothing() {
        UUID patient = newPatient();
        String appointment = bookOk(patient, "ana", "check-up", tuesday(10, 0));

        assertThat(send("POST", "/api/v1/staff/appointments/" + appointment + "/no-show", AGENTS, newKey(), null).status())
                .isEqualTo(403);
        assertThat(send("POST", appointments(patient) + "/" + appointment + "/cancel", WORKFLOWS, newKey(), null).status())
                .isEqualTo(403);

        assertThat(get(appointments(patient) + "/" + appointment).field("status")).isEqualTo("CONFIRMED");
    }
}
