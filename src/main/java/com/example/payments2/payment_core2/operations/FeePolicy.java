package com.example.payments2.payment_core2.operations;

import java.math.BigInteger;

import com.example.payments2.payment_core2.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class FeePolicy {
	private final int basisPoints;

	public FeePolicy(@Value("${payment.fees.basis-points:100}") int basisPoints) {
		if (basisPoints < 0 || basisPoints > 10000) throw new IllegalArgumentException("Invalid fee basis points.");
		this.basisPoints = basisPoints;
	}

	public Terms calculate(String amountMinor) {
		long amount = Money.positive(amountMinor);
		long fee = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(basisPoints))
				.add(BigInteger.valueOf(9999)).divide(BigInteger.valueOf(10000)).longValueExact();
		return new Terms(amount, fee, basisPoints, Money.total(amount, fee));
	}

	public Terms confirmed(String amount, String expectedFee, Integer expectedBasisPoints) {
		long expected = Money.nonnegative(expectedFee);
		if (expectedBasisPoints == null) throw Money.invalid();
		Terms terms = calculate(amount);
		if (terms.fee() != expected || terms.basisPoints() != expectedBasisPoints) {
			throw new ApiException(HttpStatus.CONFLICT, "QUOTE_CHANGED", "Confirm a new fee calculation.");
		}
		return terms;
	}

	public record Terms(long amount, long fee, int basisPoints, long total) {
	}

	public record QuoteResponse(String type, String currency, String amount_minor, String fee_minor,
			int fee_basis_points, String total_minor) {
	}

	public QuoteResponse quote(MonetaryRequests.Quote request) {
		if (!"TRANSFER".equals(request.type()) && !"WITHDRAWAL".equals(request.type())) throw Money.invalid();
		Terms terms = calculate(request.amount_minor());
		return new QuoteResponse(request.type(), "RUB", Long.toString(terms.amount()), Long.toString(terms.fee()),
				terms.basisPoints(), Long.toString(terms.total()));
	}
}
