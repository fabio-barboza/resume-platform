package dev.resumeplatform.resumeai.support;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import dev.resumeplatform.resumeai.config.DotenvEnvironmentPostProcessor;

public final class TestDatabase {
    private static final Map<String, Object> DOTENV = DotenvEnvironmentPostProcessor.readDotenvFile(Path.of(".env"));

    private static final String HOST = setting("POSTGRES_HOST", "localhost");
    private static final String PORT = setting("POSTGRES_PORT", "5432");
    private static final String USER = setting("POSTGRES_USER", "resume_agent");
    private static final String PASSWORD = setting("POSTGRES_PASSWORD", "resume_agent");
    private static final String APP_DB = setting("POSTGRES_DB", "resume_agent");
    public static final String NAME = setting("TEST_POSTGRES_DB", APP_DB + "_ai_test");

    private static boolean created;

    private TestDatabase() {
    }

    public static synchronized String url() {
        if (!created) {
            if (NAME.equals(APP_DB)) {
                throw new IllegalStateException("TEST_POSTGRES_DB não pode ser o banco da aplicação (" + APP_DB
                        + "). A suíte apaga e recria o banco que receber.");
            }
            drop();
            execute("CREATE DATABASE \"" + NAME + "\"");
            Runtime.getRuntime().addShutdownHook(new Thread(TestDatabase::drop));
            created = true;
        }
        return jdbcUrl(NAME);
    }

    public static String user() {
        return USER;
    }

    public static String password() {
        return PASSWORD;
    }

    private static void drop() {
        execute("DROP DATABASE IF EXISTS \"" + NAME + "\" WITH (FORCE)");
    }

    private static void execute(String sql) {
        try (Connection connection = DriverManager.getConnection(jdbcUrl("postgres"), USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException ex) {
            throw new IllegalStateException("Falha ao preparar o banco de teste: " + sql, ex);
        }
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + HOST + ":" + PORT + "/" + database;
    }

    private static String setting(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            Object fromFile = DOTENV.get(key);
            value = fromFile == null ? null : fromFile.toString();
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
