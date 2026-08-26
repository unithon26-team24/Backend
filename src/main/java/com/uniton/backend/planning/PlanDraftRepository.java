package com.uniton.backend.planning;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

public final class PlanDraftRepository {

    private final Connection connection;

    public PlanDraftRepository(Connection connection) {
        this.connection = connection;
    }

    public void createRevision(PlanDraftRevision revision) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }
        try {
            new PlanDraftHierarchyWriter(connection).write(revision);
            new PlanDraftContentWriter(connection).write(revision);
            if (autoCommit) {
                connection.commit();
            } else {
                connection.releaseSavepoint(savepoint);
            }
        } catch (SQLException | RuntimeException exception) {
            if (autoCommit) {
                connection.rollback();
            } else {
                connection.rollback(savepoint);
            }
            throw exception;
        } finally {
            if (autoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }
}
