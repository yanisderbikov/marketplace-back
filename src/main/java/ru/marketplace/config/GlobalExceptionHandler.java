package ru.marketplace.config;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Единый формат ошибок API: {"error": "текст по-русски"} + HTTP-статус.
 * В anyforms такие @ExceptionHandler были скопированы в каждый контроллер (и с полем message) —
 * здесь один хендлер на всё приложение, чтобы фронт всегда читал err.response.data.error.
 * Бизнес-ошибки бросаются как ResponseStatusException(status, "текст").
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException e) {
        String reason = e.getReason();
        if (reason == null || reason.isBlank()) {
            reason = defaultMessage(e.getStatusCode());
        }
        if (e.getStatusCode().is5xxServerError()) {
            log.warn("Ответ {}: {}", e.getStatusCode().value(), reason, e.getCause());
        }
        return error(e.getStatusCode(), reason);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldMessage)
                .distinct()
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = e.getBindingResult().getAllErrors().stream()
                    .map(err -> err.getDefaultMessage() == null ? "некорректное значение" : err.getDefaultMessage())
                    .distinct()
                    .collect(Collectors.joining("; "));
        }
        return error(HttpStatus.BAD_REQUEST, message.isBlank() ? "Некорректный запрос" : message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, String>> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(v -> {
                    String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
                    // Путь вида "list.size" → берём имя параметра, а не имя метода
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return field.isBlank() ? v.getMessage() : field + ": " + v.getMessage();
                })
                .distinct()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message.isBlank() ? "Некорректный запрос" : message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Некорректный запрос");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "Некорректное значение параметра: " + e.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException e) {
        return error(HttpStatus.BAD_REQUEST, "Не указан параметр: " + e.getParameterName());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        return error(HttpStatus.FORBIDDEN, "Недостаточно прав");
    }

    @ExceptionHandler({NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<Map<String, String>> handleRouting(Exception e) {
        HttpStatusCode status = ((ErrorResponse) e).getStatusCode();
        return error(status, defaultMessage(status));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleOther(Exception e) {
        // Остальные исключения Spring MVC (415, 406, нет заголовка…) уже знают свой статус —
        // не превращаем их в 500 и не шумим в лог
        if (e instanceof ErrorResponse errorResponse && !errorResponse.getStatusCode().is5xxServerError()) {
            return error(errorResponse.getStatusCode(), defaultMessage(errorResponse.getStatusCode()));
        }
        log.error("Необработанная ошибка: {}", e.getMessage(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Внутренняя ошибка");
    }

    private static String fieldMessage(FieldError err) {
        String message = err.getDefaultMessage() == null ? "некорректное значение" : err.getDefaultMessage();
        return err.getField() + ": " + message;
    }

    private static String defaultMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Некорректный запрос";
            case 401 -> "invalid auth token";
            case 403 -> "Недостаточно прав";
            case 404 -> "Не найдено";
            case 405 -> "Метод не поддерживается";
            case 409 -> "Конфликт состояния";
            case 413 -> "Слишком большой запрос";
            case 415 -> "Неподдерживаемый формат запроса";
            case 429 -> "Слишком много запросов, попробуйте позже";
            case 502 -> "Внешний сервис недоступен";
            default -> status.is5xxServerError() ? "Внутренняя ошибка" : "Ошибка запроса";
        };
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
