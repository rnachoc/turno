package io.github.rnachoc.turno.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Turno core service.
 *
 * <p>The core is the system of record: it owns availability, bookings, business rules and the
 * audit trail. AI agents never write to the database directly; they propose actions through the
 * core API, and the core validates and executes them.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@SpringBootApplication
public class TurnoCoreApplication {

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(TurnoCoreApplication.class, args);
    }
}
