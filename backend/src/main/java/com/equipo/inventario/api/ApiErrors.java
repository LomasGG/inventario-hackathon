package com.equipo.inventario.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Convierte excepciones en respuestas {"detail": "..."} con el código del contrato. */
@RestControllerAdvice
public class ApiErrors {

    /** 404: no existe la pieza, orden, ubicación o recepción. */
    public static class NotFound extends RuntimeException {
        public NotFound(String msg) { super(msg); }
    }

    /** 409: no alcanza el saldo, o ya estaba resuelto. */
    public static class Conflict extends RuntimeException {
        public Conflict(String msg) { super(msg); }
    }

    /** 422: falta el motivo u otro dato obligatorio. */
    public static class Unprocessable extends RuntimeException {
        public Unprocessable(String msg) { super(msg); }
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(Conflict.class)
    ResponseEntity<Map<String, String>> conflict(Conflict e) {
        return body(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({Unprocessable.class, MethodArgumentNotValidException.class,
                       HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, String>> unprocessable(Exception e) {
        String msg = (e instanceof Unprocessable) ? e.getMessage() : "Cuerpo inválido o incompleto";
        return body(HttpStatus.UNPROCESSABLE_CONTENT, msg);
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}