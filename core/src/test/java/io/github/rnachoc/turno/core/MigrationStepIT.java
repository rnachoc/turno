package io.github.rnachoc.turno.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the real {@code migrate} step against an empty database, as Docker Compose does. It exists
 * because this step once exited successfully without having migrated anything.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class MigrationStepIT {

    private static final String APP_PASSWORD = "step-app-password";

    private final PostgreSQLContainer postgres =
            new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE);

    @BeforeEach
    void startDatabase() {
        postgres.start();
    }

    @AfterEach
    void stopDatabase() {
        postgres.stop();
    }

    private ConfigurableApplicationContext runStep(String appPassword, String ownerPassword) {
        return new SpringApplicationBuilder(TurnoCoreApplication.class)
                .profiles("migrate")
                .properties(
                        Map.of(
                                "TURNO_DB_URL", postgres.getJdbcUrl(),
                                "TURNO_DB_USER", postgres.getUsername(),
                                "TURNO_DB_PASSWORD", ownerPassword,
                                "TURNO_DB_APP_PASSWORD", appPassword))
                .run();
    }

    private JdbcClient connectAs(String user, String password) {
        return JdbcClient.create(
                new SimpleDriverDataSource(new org.postgresql.Driver(), postgres.getJdbcUrl(), user, password));
    }

    @Test
    void itAppliesEveryMigrationAndNothingElseStarts() {
        try (ConfigurableApplicationContext context = runStep(APP_PASSWORD, postgres.getPassword())) {
            assertThat(context.getClass().getSimpleName()).as("no web server").doesNotContain("Web");
        }

        JdbcClient owner = connectAs(postgres.getUsername(), postgres.getPassword());
        Integer applied =
                owner.sql("SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL")
                        .query(Integer.class)
                        .single();
        assertThat(applied).isEqualTo(6);
        assertThat(owner.sql("SELECT to_regclass('public.appointment')::text").query(String.class).single())
                .isEqualTo("appointment");
    }

    @Test
    void theServicesRoleCanLogInWithThePasswordFromTheEnvironment() {
        runStep(APP_PASSWORD, postgres.getPassword()).close();

        JdbcClient service = connectAs(TestcontainersConfiguration.APP_ROLE, APP_PASSWORD);

        assertThat(service.sql("SELECT current_user").query(String.class).single())
                .isEqualTo(TestcontainersConfiguration.APP_ROLE);
    }

    @Test
    void runningItAgainIsHarmlessAndChangesThePassword() {
        runStep(APP_PASSWORD, postgres.getPassword()).close();

        runStep("a-new-password", postgres.getPassword()).close();

        JdbcClient service = connectAs(TestcontainersConfiguration.APP_ROLE, "a-new-password");
        assertThat(service.sql("SELECT 1").query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> connectAs(TestcontainersConfiguration.APP_ROLE, APP_PASSWORD)
                        .sql("SELECT 1")
                        .query(Integer.class)
                        .single())
                .rootCause()
                .hasMessageContaining("password authentication failed");
    }

    @Test
    void itFailsInsteadOfPretendingWhenItCannotMigrate() {
        assertThatThrownBy(() -> runStep(APP_PASSWORD, "the-wrong-owner-password"))
                .isInstanceOf(RuntimeException.class);
    }
}
