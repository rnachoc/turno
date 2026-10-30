package io.github.rnachoc.turno.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards against the tests drifting away from the database that actually runs: an automated bump
 * of the image in {@code compose.yaml} must also update the image used by Testcontainers.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
class PostgresImageSyncTest {

    @Test
    void testsUseTheSamePostgresImageAsCompose() throws IOException {
        String compose = Files.readString(Path.of("..", "compose.yaml"));

        assertThat(compose).contains("image: " + TestcontainersConfiguration.POSTGRES_IMAGE);
    }
}
