package com.example.orderrisk.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads service configuration from {@code application.properties} on the classpath.
 * <p>
 * Every key can be overridden by an environment variable (used by Docker Compose) or a
 * JVM system property. The environment variable name is derived from the key:
 * {@code processing.workerThreads} becomes {@code PROCESSING_WORKER_THREADS}.
 */
public final class ServiceConfig {

    private final Properties properties = new Properties();

    private ServiceConfig() {
    }

    public static ServiceConfig load() {
        ServiceConfig config = new ServiceConfig();
        try (InputStream in = ServiceConfig.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (in == null) {
                throw new IllegalStateException("application.properties not found on classpath");
            }
            config.properties.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read application.properties", e);
        }
        return config;
    }

    public String get(String key) {
        String value = System.getProperty(key);
        if (value == null) {
            value = System.getenv(toEnvName(key));
        }
        if (value == null) {
            value = properties.getProperty(key);
        }
        if (value == null) {
            throw new IllegalStateException("Missing configuration value: " + key);
        }
        return value.trim();
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key));
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(get(key));
    }

    /** {@code processing.workerThreads -> PROCESSING_WORKER_THREADS}. */
    static String toEnvName(String key) {
        StringBuilder sb = new StringBuilder();
        for (char c : key.toCharArray()) {
            if (c == '.' || c == '-') {
                sb.append('_');
            } else if (Character.isUpperCase(c)) {
                sb.append('_').append(c);
            } else {
                sb.append(Character.toUpperCase(c));
            }
        }
        return sb.toString();
    }
}
