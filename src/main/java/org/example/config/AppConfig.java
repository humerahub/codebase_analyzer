package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads config out of src/main/resources/application.properties. */
public final class AppConfig {

    public static final String SOURCE_ROOT;
    public static final String DOT_OUTPUT_PATH;
    public static final String DB_URL;
    public static final String DB_USER;
    public static final String DB_PASSWORD;

    static {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            props.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load application.properties", e);
        }

        SOURCE_ROOT = props.getProperty("source.root");
        DOT_OUTPUT_PATH = props.getProperty("dot.output.path");
        DB_URL = props.getProperty("db.url");
        DB_USER = props.getProperty("db.user");
        DB_PASSWORD = props.getProperty("db.password");
    }

    private AppConfig() {
    }
}
