package com.uniton.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostgresIntegrationSupportTest extends PostgresIntegrationSupport {

    @BeforeEach
    void migrateFreshDatabase() {
        resetAndMigrate();
    }

    @Test
    void migratesFreshDatabaseWhenTestStarts() throws SQLException {
        // Given
        var migrationQuery = "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank";

        // When
        try (var connection = openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(migrationQuery)) {

            // Then
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("version")).isEqualTo("001");
            assertThat(rows.getBoolean("success")).isTrue();
        }
    }

    @Test
    void removesStaleDatabaseStateWhenDatabaseIsReset() throws SQLException {
        // Given
        try (var connection = openConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE task8_stale_state (id bigint PRIMARY KEY)");
        }

        // When
        resetAndMigrate();

        // Then
        try (var connection = openConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT to_regclass('task8_stale_state')")) {
            rows.next();
            assertThat(rows.getString(1)).isNull();
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
