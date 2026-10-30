package io.github.rnachoc.turno.core;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Applies the migrations in the one-shot {@code migrate} step.
 *
 * <p>It is explicit because, with lazy initialization, Spring's own Flyway initializer never runs
 * and the step would exit successfully without having migrated anything. A failure here stops the
 * application, so the step exits with an error and the service does not start.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@Component
@Profile("migrate")
class MigrationRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

    private final ObjectProvider<Flyway> flyway;

    MigrationRunner(ObjectProvider<Flyway> flyway) {
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) {
        int applied = flyway.getObject().migrate().migrationsExecuted;
        LOG.info("Migrations applied: {}", applied);
    }
}
