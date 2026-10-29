package dev.hookflow.sdk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The one Jackson mapper shared by every client instance.
 *
 * <p>{@link ObjectMapper} is thread-safe once configured, and the SDK never mutates
 * the configuration after class initialisation. Keeping it static also means callers
 * do not pay for a mapper per {@code HookFlowClient}.
 */
final class Json {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** Serialises a string as a JSON string literal, quotes included. */
    static String quote(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to encode JSON string", e);
        }
    }
}
