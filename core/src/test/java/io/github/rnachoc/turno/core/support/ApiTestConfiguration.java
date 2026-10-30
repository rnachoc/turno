package io.github.rnachoc.turno.core.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces the clock with one under test control, and adds an endpoint that fails unexpectedly.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@TestConfiguration(proxyBeanMethods = false)
public class ApiTestConfiguration {

    /** Text that must never reach a response, even though the exception carries it. */
    public static final String INTERNAL_SECRET = "jdbc:postgresql://internal-host/secret-detail";

    /** Fails like a bug would, to prove what a caller gets to see of an unexpected error. */
    @RestController
    static class FailingController {

        @GetMapping("/api/v1/test/failure")
        String fail() {
            throw new IllegalStateException(INTERNAL_SECRET);
        }
    }

    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
