package com.uniton.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.uniton.backend.persistence.ProjectRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresIntegrationSupportTest extends PostgresIntegrationSupport {

    @BeforeEach
    void migrateFreshDatabase() {
        cleanAndMigrate();
    }

    @Test
    void supportsRepositoryConsumerOnFreshMigratedDatabase() throws SQLException {
        // Given
        var projectId = UUID.randomUUID();
        var setup = new ProjectRepository.ProjectSetup(
                projectId,
                "Harness consumer",
                "Fresh migration",
                Instant.parse("2026-09-01T00:00:00Z"),
                "UTC",
                UUID.randomUUID(),
                "U-OWNER",
                "Owner",
                List.of("검증"));

        // When
        try (var connection = connection()) {
            new ProjectRepository(connection).createProject(setup);
        }

        // Then
        assertThat(projectCount()).isEqualTo(1);
    }

    @Test
    void removesStaleDatabaseStateWhenDatabaseIsReset() throws SQLException {
        // Given
        try (var connection = connection()) {
            new ProjectRepository(connection).createProject(new ProjectRepository.ProjectSetup(
                    UUID.randomUUID(),
                    "Stale consumer",
                    "Reset migration",
                    Instant.parse("2026-09-01T00:00:00Z"),
                    "UTC",
                    UUID.randomUUID(),
                    "U-OWNER",
                    "Owner",
                    List.of("검증")));
        }

        // When
        cleanAndMigrate();

        // Then
        assertThat(projectCount()).isZero();
    }

    private int projectCount() throws SQLException {
        try (var connection = connection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT count(*) FROM projects")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    @Test
    void suppliesDeterministicClockAndIds() {
        // Given
        var values = new DeterministicTestValues(Instant.parse("2026-08-26T00:00:00Z"), 41L);

        // When
        var firstId = values.nextId();
        var secondId = values.nextId();

        // Then
        assertThat(values.clock().instant()).isEqualTo(Instant.parse("2026-08-26T00:00:00Z"));
        assertThat(firstId).isEqualTo("test-id-41");
        assertThat(secondId).isEqualTo("test-id-42");
    }
}
