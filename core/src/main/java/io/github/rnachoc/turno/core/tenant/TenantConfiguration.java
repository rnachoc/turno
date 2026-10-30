package io.github.rnachoc.turno.core.tenant;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the clinic configuration and the clock used by every business rule.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Configuration(proxyBeanMethods = false)
public class TenantConfiguration {

    /**
     * Loads the tenant at startup, so that an invalid file stops the service immediately.
     *
     * @param file path of the tenant file
     * @return the validated tenant
     */
    @Bean
    Tenant tenant(@Value("${turno.tenant-file}") Path file) {
        return TenantLoader.load(file);
    }

    /**
     * Provides the time. Rules never read the system clock directly, so tests can place them
     * exactly on a boundary. Millisecond precision survives the round trip through the database.
     *
     * @return the system clock, in UTC
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofMillis(1));
    }
}
