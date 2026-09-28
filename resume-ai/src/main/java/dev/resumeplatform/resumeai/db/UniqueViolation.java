package dev.resumeplatform.resumeai.db;

import java.sql.SQLException;

public final class UniqueViolation {
    private static final String UNIQUE_VIOLATION = "23505";

    private UniqueViolation() {
    }

    public static boolean isCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
