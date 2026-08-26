package com.uniton.backend.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.uniton.backend.persistence.PostgresIntegrationSupport;
import com.uniton.backend.persistence.ProjectRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomationJobRepositoryTest extends PostgresIntegrationSupport {

    private UUID projectId;

    @BeforeEach
    void migrateFreshDatabase() throws SQLException {
        cleanAndMigrate();
        projectId = UUID.randomUUID();
        try (Connection connection = connection()) {
            new ProjectRepository(connection).createProject(new ProjectRepository.ProjectSetup(
                    projectId, "Jobs", "Durable work", Instant.now().plusSeconds(3600), "UTC",
                    UUID.randomUUID(), "U-OWNER", "Owner", List.of("운영")));
        }
    }

    @Test
    void duplicateJobKeyCannotCreateTwoWorkItems() throws SQLException {
        // Given
        try (Connection connection = connection()) {
            AutomationJobRepository jobs = new AutomationJobRepository(connection);
            jobs.enqueue(projectId, "generate_plan_draft", "project:request-1", Instant.now());

            // When / Then
            assertThatThrownBy(() -> jobs.enqueue(
                            projectId, "generate_plan_draft", "project:request-1", Instant.now()))
                    .isInstanceOf(SQLException.class);
        }
        assertThat(jobCount()).isEqualTo(1);
        System.out.println("DATA_SURFACE duplicate_job_key=rejected durable_jobs=1");
    }

    @Test
    void concurrentClaimersProduceExactlyOneDurableLeaseWinner() throws Exception {
        // Given
        try (Connection connection = connection()) {
            new AutomationJobRepository(connection)
                    .enqueue(projectId, "generate_plan_draft", "claim-once",
                            Instant.parse("2100-01-01T00:00:00Z"));
            makeJobDueInDatabase(connection, "claim-once");
        }

        // When
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Callable<Integer> claim = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("claimers did not reach the start barrier");
                }
                try (Connection connection = connection()) {
                    return new AutomationJobRepository(connection).claimNext("worker-" + UUID.randomUUID())
                            .isPresent() ? 1 : 0;
                }
            };
            var firstClaim = executor.submit(claim);
            var secondClaim = executor.submit(claim);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int winners = firstClaim.get() + secondClaim.get();

            // Then
            assertThat(winners).isEqualTo(1);
        }
        assertThat(runningJobCount()).isEqualTo(1);
        System.out.println(
                "DATA_SURFACE concurrent_claimers=2 durable_lease_winners=1 eligibility_clock=postgres");
    }

    @Test
    void agendaTimerUsesServerDerivedRunAfterAndNinetySecondLease() throws SQLException {
        // Given
        Instant start = Instant.parse("2026-01-01T00:00:00.000000123Z");
        AutomationJobRepository.AgendaTimer timer = new AutomationJobRepository.AgendaTimer(
                projectId, UUID.randomUUID(), UUID.randomUUID(), "80_percent", start, Duration.ofMinutes(10));

        // When
        try (Connection connection = connection()) {
            AutomationJobRepository jobs = new AutomationJobRepository(connection);
            AutomationJobRepository.Job enqueued = jobs.enqueueAgendaTimer(timer);
            AutomationJobRepository.Job claimed = jobs.claimNext("timer-worker").orElseThrow();

            // Then
            assertThat(enqueued.runAfter())
                    .isEqualTo(start.truncatedTo(ChronoUnit.MICROS).plus(Duration.ofMinutes(8)));
            assertThat(Duration.between(claimed.leasedAt(), claimed.leaseExpiresAt()).getSeconds())
                    .isEqualTo(90);
            System.out.println("DATA_SURFACE timer_run_after=server_derived lease_seconds=90");
        }
    }

    @Test
    void expiredAgendaTimerLeaseCanBeReclaimedOnce() throws SQLException {
        // Given
        try (Connection connection = connection()) {
            AutomationJobRepository jobs = new AutomationJobRepository(connection);
            jobs.enqueueAgendaTimer(new AutomationJobRepository.AgendaTimer(
                    projectId, UUID.randomUUID(), UUID.randomUUID(), "expiry",
                    Instant.now().minusSeconds(600), Duration.ofMinutes(5)));
            assertThat(jobs.claimNext("first-worker")).isPresent();
            try (var expire = connection.prepareStatement("""
                    WITH stale_at AS (
                        SELECT clock_timestamp() AS value
                    )
                    UPDATE automation_jobs
                    SET leased_at = stale_at.value - interval '91 seconds',
                        lease_expires_at = stale_at.value - interval '1 second'
                    FROM stale_at
                    """)) {
                expire.executeUpdate();
            }

            // When
            AutomationJobRepository.Job reclaimed = jobs.claimNext("recovery-worker").orElseThrow();

            // Then
            assertThat(reclaimed.leaseOwner()).isEqualTo("recovery-worker");
            assertThat(reclaimed.attemptCount()).isEqualTo(2);
            System.out.println("DATA_SURFACE expired_lease=reclaimed attempt_count=2");
        }
    }

    @Test
    void malformedTimerPhaseIsRejected() {
        // Given
        AutomationJobRepository.AgendaTimer malformed = new AutomationJobRepository.AgendaTimer(
                projectId, UUID.randomUUID(), UUID.randomUUID(), "halfway",
                Instant.now(), Duration.ofMinutes(5));

        // When / Then
        assertThatThrownBy(() -> {
            try (Connection connection = connection()) {
                new AutomationJobRepository(connection).enqueueAgendaTimer(malformed);
            }
        }).isInstanceOf(IllegalArgumentException.class);
        System.out.println("DATA_SURFACE malformed_timer_phase=rejected");
    }

    @Test
    void duplicateAgendaTimerKeyIsRejected() throws SQLException {
        // Given
        AutomationJobRepository.AgendaTimer timer = new AutomationJobRepository.AgendaTimer(
                projectId, UUID.randomUUID(), UUID.randomUUID(), "expiry",
                Instant.now(), Duration.ofMinutes(5));
        try (Connection connection = connection()) {
            AutomationJobRepository jobs = new AutomationJobRepository(connection);
            jobs.enqueueAgendaTimer(timer);

            // When / Then
            assertThatThrownBy(() -> jobs.enqueueAgendaTimer(timer)).isInstanceOf(SQLException.class);
        }
        assertThat(jobCount()).isEqualTo(1);
        System.out.println("DATA_SURFACE duplicate_timer=rejected durable_jobs=1");
    }

    @Test
    void recurringAgendaTimerShapeIsRejectedByDatabase() throws SQLException {
        // Given
        try (Connection connection = connection();
                var insert = connection.prepareStatement("""
                        INSERT INTO automation_jobs
                          (id, project_id, job_type, job_key, run_after, meeting_id, agenda_id,
                           timer_phase, agenda_starts_at, agenda_duration_seconds, recurrence_rule)
                        VALUES (?, ?, 'meeting_agenda_timer', ?, clock_timestamp(), ?, ?,
                                'expiry', clock_timestamp(), 300, 'PT5M')
                        """)) {
            UUID meetingId = UUID.randomUUID();
            UUID agendaId = UUID.randomUUID();
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, projectId);
            insert.setString(3, "meeting_agenda_timer:" + meetingId + ":" + agendaId + ":expiry");
            insert.setObject(4, meetingId);
            insert.setObject(5, agendaId);

            // When / Then
            assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class);
        }
        assertThat(jobCount()).isZero();
        System.out.println("DATA_SURFACE recurring_timer_shape=rejected leaked_jobs=0");
    }

    @Test
    void terminalJobWithoutFinishedAtIsRejected() throws SQLException {
        // Given
        try (Connection connection = connection()) {
            AutomationJobRepository jobs = new AutomationJobRepository(connection);
            UUID jobId = jobs.enqueue(
                    projectId, "generate_plan_draft", "terminal-state", Instant.now()).id();

            // When / Then
            assertThatThrownBy(() -> setSucceededWithoutFinishedAt(connection, jobId))
                    .isInstanceOf(SQLException.class);
        }
        System.out.println("DATA_SURFACE incomplete_terminal_state=rejected");
    }

    private void setSucceededWithoutFinishedAt(Connection connection, UUID jobId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE automation_jobs SET status = 'succeeded' WHERE id = ?")) {
            statement.setObject(1, jobId);
            statement.executeUpdate();
        }
    }

    private int jobCount() throws SQLException {
        return scalar("SELECT count(*) FROM automation_jobs");
    }

    private void makeJobDueInDatabase(Connection connection, String jobKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE automation_jobs
                SET run_after = clock_timestamp() - interval '1 second'
                WHERE job_key = ?
                """)) {
            statement.setString(1, jobKey);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private int runningJobCount() throws SQLException {
        return scalar("SELECT count(*) FROM automation_jobs WHERE status = 'running' AND lease_owner IS NOT NULL");
    }

    private int scalar(String sql) throws SQLException {
        try (Connection connection = connection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
