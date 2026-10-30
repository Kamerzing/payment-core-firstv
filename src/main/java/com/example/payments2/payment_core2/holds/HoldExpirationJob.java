package com.example.payments2.payment_core2.holds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "payment.holds.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpirationJob {
	private static final Logger LOG = LoggerFactory.getLogger(HoldExpirationJob.class);
	private final HoldRepository holds;
	private final HoldService service;
	public HoldExpirationJob(HoldRepository holds, HoldService service) { this.holds = holds; this.service = service; }

	@Scheduled(fixedDelay = 30000, initialDelay = 0)
	public void expireDue() {
		for (var id : holds.due()) {
			try { service.expire(id); }
			catch (RuntimeException exception) { LOG.error("Hold expiration failed: hold_id={}, type={}", id, exception.getClass().getName()); }
		}
	}
}
