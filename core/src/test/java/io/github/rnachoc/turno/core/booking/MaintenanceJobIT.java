package io.github.rnachoc.turno.core.booking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rnachoc.turno.core.TestcontainersConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * The other tests run with the jobs off, to stop timers from interfering. This one turns them on, as
 * in production, and checks that they are really scheduled.
 *
 * @author Ignacio Rodriguez Caro &lt;rnachoccontact@gmail.com&gt;
 */
@SpringBootTest(properties = "turno.jobs.enabled=true")
@Import(TestcontainersConfiguration.class)
class MaintenanceJobIT {

    @Autowired
    private List<ScheduledTaskHolder> schedulers;

    @Test
    void bothJobsAreScheduled() {
        List<String> jobs =
                schedulers.stream()
                        .flatMap(holder -> holder.getScheduledTasks().stream())
                        .map(task -> task.getTask().getRunnable().toString())
                        .toList();

        assertThat(jobs)
                .anyMatch(job -> job.contains("MaintenanceJob.expireHolds"))
                .anyMatch(job -> job.contains("MaintenanceJob.purgeIdempotencyKeys"));
    }
}
