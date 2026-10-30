package com.example.payments2.payment_core2;

import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.operations.FeePolicy;
import com.example.payments2.payment_core2.operations.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class MoneyTests {
	@Test
	void roundsFeesUpWithoutFloatingPointOrIntermediateOverflow() {
		var fees = new FeePolicy(100);
		assertThat(fees.calculate("1").fee()).isEqualTo(1);
		assertThat(fees.calculate("99").fee()).isEqualTo(1);
		assertThat(fees.calculate("100").fee()).isEqualTo(1);
		assertThat(fees.calculate("101").fee()).isEqualTo(2);
		assertThat(fees.calculate("9000000000000000000").fee()).isEqualTo(90000000000000000L);
		assertThat(new FeePolicy(0).calculate(Long.toString(Long.MAX_VALUE)).fee()).isZero();
		assertThat(new FeePolicy(10000).calculate("10").total()).isEqualTo(20);
	}

	@Test
	void rejectsNoncanonicalAmountsAndCheckedTotalOverflow() {
		for (String value : new String[] {null, "", "0", "-1", "+1", "01", "1.0", "1e2", " 1", "9223372036854775808"}) {
			assertThatThrownBy(() -> Money.positive(value)).isInstanceOf(ApiException.class);
		}
		assertThatThrownBy(() -> new FeePolicy(100).calculate(Long.toString(Long.MAX_VALUE)))
				.isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("AMOUNT_OVERFLOW");
		assertThatThrownBy(() -> new FeePolicy(101).confirmed("100", "1", 100))
				.isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo("QUOTE_CHANGED");
		assertThatThrownBy(() -> new FeePolicy(-1)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new FeePolicy(10001)).isInstanceOf(IllegalArgumentException.class);
	}
}
