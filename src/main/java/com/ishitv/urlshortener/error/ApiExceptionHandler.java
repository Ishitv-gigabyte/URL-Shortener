package com.ishitv.urlshortener.error;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.WebUtils;

import com.ishitv.urlshortener.error.ValidationErrors.Item;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.InvalidNullException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Translates exceptions into the FastAPI service's error responses:
 * <ul>
 *   <li>404/500 from the app: {@code {"detail": "<message>"}}</li>
 *   <li>request validation: 422 with Pydantic's error list (shapes recorded in
 *       src/test/resources/golden/python-responses.txt)</li>
 *   <li>framework errors (unknown path, wrong method, ...): {@code {"detail": "<reason phrase>"}}</li>
 *   <li>anything unexpected: 500 text/plain "Internal Server Error", like Starlette</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final List<Object> BODY = List.of("body");
    private static final List<Object> BODY_URL = List.of("body", "original_url");
    private static final String NOT_AN_OBJECT = "Input should be a valid dictionary or object to extract fields from";

    private final ObjectMapper objectMapper;

    public ApiExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(ShortCodeNotFoundException.class)
    public ResponseEntity<ErrorDetail> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorDetail("URL not found"));
    }

    @ExceptionHandler(CodeGenerationException.class)
    public ResponseEntity<ErrorDetail> codeGenerationFailed(CodeGenerationException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorDetail(e.getMessage()));
    }

    /** Bean Validation failures on a parsed body: the field was absent, too short or too long. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrors> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        FieldError error = e.getBindingResult().getFieldError();
        if (error == null || error.getRejectedValue() == null) {
            return unprocessable(new Item("missing", BODY_URL, "Field required", cachedBodyAsJson(request), null));
        }
        String value = (String) error.getRejectedValue();
        if (value.length() < 10) {
            return unprocessable(new Item("string_too_short", BODY_URL, "String should have at least 10 characters",
                    value, Map.of("min_length", 10)));
        }
        return unprocessable(tooLong(value));
    }

    @ExceptionHandler(UrlTooLongException.class)
    public ResponseEntity<ValidationErrors> urlTooLong(UrlTooLongException e) {
        return unprocessable(tooLong(e.getSubmittedUrl()));
    }

    /** The body could not be turned into a ShortenRequest at all. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ValidationErrors> unreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        Throwable cause = e.getMostSpecificCause();

        if (cause instanceof InvalidNullException) {
            return unprocessable(stringType(null));
        }
        if (cause instanceof InvalidFormatException invalid && !invalid.getPath().isEmpty()) {
            return unprocessable(stringType(invalid.getValue()));
        }
        if (cause instanceof MismatchedInputException mismatch && mismatch.getPath().isEmpty()) {
            return unprocessable(new Item("model_attributes_type", BODY, NOT_AN_OBJECT, cachedBodyAsJson(request), null));
        }
        if (cause instanceof StreamReadException malformed) {
            // Byte-stream input only tracks byte offsets; Python reports a character index (equal for ASCII).
            var location = malformed.getLocation();
            long position = location.getCharOffset() >= 0 ? location.getCharOffset() : location.getByteOffset();
            return unprocessable(new Item("json_invalid", List.of("body", position), "JSON decode error", Map.of(),
                    Map.of("error", "Invalid JSON")));
        }
        // No body at all.
        return unprocessable(new Item("missing", BODY, "Field required", null, null));
    }

    /**
     * FastAPI only parses JSON for a JSON content type; anything else is validated as a raw string and
     * fails with model_attributes_type. Spring already peeked at the first byte (to detect an empty
     * body), so drain the rest through the caching wrapper and take the full cached copy.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ValidationErrors> notJson(HttpServletRequest request) throws java.io.IOException {
        ContentCachingRequestWrapper cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        byte[] bytes;
        if (cached != null) {
            cached.getInputStream().readAllBytes();
            bytes = cached.getContentAsByteArray();
        } else {
            bytes = request.getInputStream().readAllBytes();
        }
        String body = new String(bytes, StandardCharsets.UTF_8);
        if (body.isEmpty()) {
            return unprocessable(new Item("missing", BODY, "Field required", null, null));
        }
        return unprocessable(new Item("model_attributes_type", BODY, NOT_AN_OBJECT, body, null));
    }

    /**
     * Everything else. Spring MVC's own exceptions (no route, wrong method, ...) implement ErrorResponse
     * and carry their status and headers (e.g. Allow); render them the way Starlette does.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> fallback(Exception e) {
        if (e instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            String reason = HttpStatus.resolve(status.value()) != null
                    ? HttpStatus.valueOf(status.value()).getReasonPhrase()
                    : "Error";
            return ResponseEntity.status(status).headers(errorResponse.getHeaders()).body(new ErrorDetail(reason));
        }
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal Server Error");
    }

    private static Item stringType(Object input) {
        return new Item("string_type", BODY_URL, "Input should be a valid string", input, null);
    }

    private static Item tooLong(String input) {
        return new Item("string_too_long", BODY_URL, "String should have at most 1500 characters", input,
                Map.of("max_length", 1500));
    }

    private static ResponseEntity<ValidationErrors> unprocessable(Item item) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(ValidationErrors.of(item));
    }

    /** Pydantic echoes the whole parsed body as "input" for body-level errors. */
    private Object cachedBodyAsJson(HttpServletRequest request) {
        ContentCachingRequestWrapper cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper.class);
        if (cached == null || cached.getContentAsByteArray().length == 0) {
            return Map.of();
        }
        try {
            JsonNode body = objectMapper.readTree(cached.getContentAsByteArray());
            return body;
        } catch (JacksonException unreadable) {
            return Map.of();
        }
    }
}
