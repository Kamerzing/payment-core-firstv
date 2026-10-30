package com.example.payments2.payment_core2.common.web;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;

public record ApiError(int status, String code, String message,
		@JsonProperty("request_id") String requestId, Instant timestamp) {

	public static ApiError of(HttpStatusCode status, HttpServletRequest request) {
		String code = switch (status.value()) {
			case 400 -> "INVALID_REQUEST";
			case 401 -> "UNAUTHORIZED";
			case 403 -> "ACCESS_DENIED";
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 406 -> "NOT_ACCEPTABLE";
			case 409 -> "CONFLICT";
			case 415 -> "UNSUPPORTED_MEDIA_TYPE";
			case 429 -> "TOO_MANY_REQUESTS";
			case 503 -> "SERVICE_UNAVAILABLE";
			default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "HTTP_ERROR";
		};
		String message = switch (code) {
			case "INVALID_REQUEST" -> "The request is invalid.";
			case "UNAUTHORIZED" -> "Authentication is required.";
			case "ACCESS_DENIED" -> "Access is denied.";
			case "NOT_FOUND" -> "The resource was not found.";
			case "METHOD_NOT_ALLOWED" -> "The HTTP method is not allowed.";
			case "NOT_ACCEPTABLE" -> "The requested response format is not supported.";
			case "CONFLICT" -> "The request conflicts with the current state.";
			case "UNSUPPORTED_MEDIA_TYPE" -> "The request content type is not supported.";
			case "TOO_MANY_REQUESTS" -> "Too many requests.";
			case "SERVICE_UNAVAILABLE" -> "The service is temporarily unavailable.";
			default -> "The request could not be completed.";
		};
		return new ApiError(status.value(), code, message,
				(String) request.getAttribute(RequestIdFilter.ATTRIBUTE), Instant.now());
	}

	public static ApiError csrf(HttpServletRequest request) {
		return new ApiError(403, "CSRF_INVALID", "A valid CSRF token is required.",
				(String) request.getAttribute(RequestIdFilter.ATTRIBUTE), Instant.now());
	}
}
