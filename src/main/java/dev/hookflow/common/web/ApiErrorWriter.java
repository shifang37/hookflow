package dev.hookflow.common.web;

import dev.hookflow.common.error.ApiError;
import dev.hookflow.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Writes the standard error body from places that sit outside the MVC exception
 * handling pipeline — filters, in practice. Keeping the shape identical matters:
 * a client should never have to parse two different error formats.
 */
@Component
public class ApiErrorWriter {

    private final ObjectMapper objectMapper;

    public ApiErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      ErrorCode errorCode, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiError body = ApiError.of(errorCode, message, RequestIdFilter.currentRequestId(request));
        response.getWriter().write(objectMapper.writeValueAsString(body));
        response.flushBuffer();
    }
}
