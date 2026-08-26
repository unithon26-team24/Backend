package com.uniton.backend.jobs;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AutomationJobRepository {

    private static final Set<String> TIMER_PHASES = Set.of("80_percent", "expiry");
    private final Connection connection;

    public AutomationJobRepository(Connection connection) {
        this.connection = connection;
    }

    public Job enqueue(UUID projectId, String jobType, String jobKey, Instant runAfter)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO automation_jobs (id, project_id, job_type, job_key, run_after)
                VALUES (?, ?, ?, ?, ?)
                RETURNING *
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, projectId);
            statement.setString(3, jobType);
            statement.setString(4, jobKey);
            statement.setTimestamp(5, Timestamp.from(runAfter));
            return returnedJob(statement.executeQuery());
        }
    }

    public Job enqueueAgendaTimer(AgendaTimer timer) throws SQLException {
        if (!TIMER_PHASES.contains(timer.phase())) {
            throw new IllegalArgumentException("timer phase must be 80_percent or expiry");
        }
        if (timer.duration().isZero() || timer.duration().isNegative()) {
            throw new IllegalArgumentException("timer duration must be positive");
        }
        String key = "meeting_agenda_timer:%s:%s:%s"
                .formatted(timer.meetingId(), timer.agendaId(), timer.phase());
        try (var statement = connection.prepareStatement("""
                INSERT INTO automation_jobs
                  (id, project_id, job_type, job_key, run_after, meeting_id, agenda_id,
                   timer_phase, agenda_starts_at, agenda_duration_seconds)
                VALUES (?, ?, 'meeting_agenda_timer', ?,
                        ?::timestamptz + (? * interval '1 second'
                            * CASE WHEN ? = '80_percent' THEN 0.8 ELSE 1 END),
                        ?, ?, ?, ?, ?)
                RETURNING *
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, timer.projectId());
            statement.setString(3, key);
            statement.setTimestamp(4, Timestamp.from(timer.agendaStartsAt()));
            statement.setLong(5, timer.duration().getSeconds());
            statement.setString(6, timer.phase());
            statement.setObject(7, timer.meetingId());
            statement.setObject(8, timer.agendaId());
            statement.setString(9, timer.phase());
            statement.setTimestamp(10, Timestamp.from(timer.agendaStartsAt()));
            statement.setLong(11, timer.duration().getSeconds());
            return returnedJob(statement.executeQuery());
        }
    }

    public Optional<Job> claimNext(String leaseOwner) throws SQLException {
        try (var statement = connection.prepareStatement("""
                WITH claimed_at AS (
                    SELECT clock_timestamp() AS value
                ), claimable AS (
                    SELECT id
                    FROM automation_jobs, claimed_at
                    WHERE run_after <= claimed_at.value
                      AND (status = 'pending'
                           OR (status = 'running' AND lease_expires_at <= claimed_at.value))
                    ORDER BY run_after, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE automation_jobs job
                SET status = 'running',
                    attempt_count = attempt_count + 1,
                    lease_owner = ?,
                    leased_at = claimed_at.value,
                    lease_expires_at = claimed_at.value + interval '90 seconds',
                    updated_at = claimed_at.value
                FROM claimable, claimed_at
                WHERE job.id = claimable.id
                RETURNING job.*
                """)) {
            statement.setString(1, leaseOwner);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        }
    }

    private Job returnedJob(ResultSet rows) throws SQLException {
        try (rows) {
            if (!rows.next()) {
                throw new SQLException("insert did not return an automation job");
            }
            return map(rows);
        }
    }

    private Job map(ResultSet rows) throws SQLException {
        return new Job(
                rows.getObject("id", UUID.class),
                rows.getString("job_type"),
                rows.getString("job_key"),
                rows.getString("status"),
                rows.getInt("attempt_count"),
                instant(rows, "run_after"),
                rows.getString("lease_owner"),
                instant(rows, "leased_at"),
                instant(rows, "lease_expires_at"));
    }

    private Instant instant(ResultSet rows, String column) throws SQLException {
        OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record AgendaTimer(
            UUID projectId,
            UUID meetingId,
            UUID agendaId,
            String phase,
            Instant agendaStartsAt,
            Duration duration) {}

    public record Job(
            UUID id,
            String type,
            String key,
            String status,
            int attemptCount,
            Instant runAfter,
            String leaseOwner,
            Instant leasedAt,
            Instant leaseExpiresAt) {}
}
