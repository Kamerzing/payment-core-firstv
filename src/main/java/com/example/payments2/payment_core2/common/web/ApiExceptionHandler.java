package com.example.payments2.payment_core2.common.web;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(TransientDataAccessException.class)
	public ResponseEntity<ApiError> handleTransientFailure(TransientDataAccessException exception, HttpServletRequest request) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(503, "RETRYABLE_FAILURE",
				"Retry the request with the same Idempotency-Key.",
				(String) request.getAttribute(RequestIdFilter.ATTRIBUTE), Instant.now()));
	}

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ApiError> handleApiException(ApiException exception, HttpServletRequest request) {
		return ResponseEntity.status(exception.status()).body(new ApiError(exception.status().value(),
				exception.code(), exception.getMessage(),
				(String) request.getAttribute(RequestIdFilter.ATTRIBUTE), Instant.now()));
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var servletRequest = ((ServletWebRequest) request).getRequest();
		return super.handleExceptionInternal(exception, ApiError.of(status, servletRequest),
				headers, status, request);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
		// Exception messages may contain SQL parameters or credentials. Correlate by request_id.
		LOG.error("Unhandled request failure: type={}", exception.getClass().getName());
		return ResponseEntity.internalServerError().body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, request));
	}
}
