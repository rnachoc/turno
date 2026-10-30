package io.github.rnachoc.turno.core;

import org.springframework.boot.SpringApplication;

/**
 * Runs the core locally against a throwaway PostgreSQL container, with no Docker Compose and no
 * environment variables required. Useful for quick manual checks from the IDE.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
public class TestTurnoCoreApplication {

    /**
     * Starts the application with {@link TestcontainersConfiguration} applied.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.from(TurnoCoreApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
