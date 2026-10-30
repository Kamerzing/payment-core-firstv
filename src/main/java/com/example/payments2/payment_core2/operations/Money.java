package com.example.payments2.payment_core2.operations;

import com.example.payments2.payment_core2.common.web.ApiException;
import org.springframework.http.HttpStatus;

public final class Money {
	private Money() {
	}

	public static long positive(String value) {
		long amount = nonnegative(value);
		if (amount == 0) throw invalid();
		return amount;
	}

	public static long nonnegative(String value) {
		if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
		try {
			return Long.parseLong(value);
		} catch (NumberFormatException exception) {
			throw invalid();
		}
	}

	public static long total(long amount, long fee) {
		try {
			return Math.addExact(amount, fee);
		} catch (ArithmeticException exception) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "AMOUNT_OVERFLOW", "The amount and fee are too large.");
		}
	}

	public static ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
	}
}
