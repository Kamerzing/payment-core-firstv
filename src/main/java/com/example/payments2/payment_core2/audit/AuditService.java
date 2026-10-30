package com.example.payments2.payment_core2.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.payments2.payment_core2.operations.Money;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
	private final JdbcTemplate jdbc;

	public AuditService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void append(UUID actor, String action, UUID operation) {
		String requestId = MDC.get("request_id");
		jdbc.update("INSERT INTO audit_event (id, actor_id, action, operation_id, request_id) VALUES (?, ?, ?, ?, ?)",
				UUID.randomUUID(), actor, action, operation, requestId == null ? UUID.randomUUID().toString() : requestId);
	}

	@Transactional(readOnly = true)
	public List<Event> list(int limit, int offset) {
		if (limit < 1 || limit > 100 || offset < 0) throw Money.invalid();
		return jdbc.query("SELECT * FROM audit_event ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
				(rs, row) -> new Event(rs.getObject("id", UUID.class), rs.getObject("actor_id", UUID.class), rs.getString("action"),
						rs.getObject("operation_id", UUID.class), rs.getString("request_id"), rs.getTimestamp("created_at").toInstant()), limit, offset);
	}

	public record Event(UUID id, UUID actor_id, String action, UUID operation_id, String request_id, Instant created_at) {
	}
}
