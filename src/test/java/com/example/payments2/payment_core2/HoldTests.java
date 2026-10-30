package com.example.payments2.payment_core2;

import java.util.*;
import java.util.concurrent.*;
import com.example.payments2.payment_core2.holds.*;
import com.example.payments2.payment_core2.operations.*;
import com.example.payments2.payment_core2.ledger.*;
import com.example.payments2.payment_core2.wallets.WalletRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.payments2.payment_core2.common.web.ApiException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class HoldTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired WalletRepository wallets;
	@Autowired HoldService holds;
	@Autowired HoldRepository repository;
	@Autowired OperationService operations;
	@Autowired ReconciliationService reconciliation;
	@Autowired PlatformTransactionManager transactions;
	@Autowired MockMvc mvc;
	UUID alice, bob;

	@BeforeEach void setup() {
		jdbc.execute("TRUNCATE audit_event, command_result, ledger_posting, payment_operation, ledger_account, wallet, app_user, login_attempt_bucket, spring_session CASCADE");
		jdbc.update("INSERT INTO ledger_account (id, kind) VALUES (?, 'SETTLEMENT'), (?, 'FEES')", LedgerRepository.SETTLEMENT, LedgerRepository.FEES);
		create("admin", "ADMIN"); alice = create("alice", "USER"); bob = create("bob", "USER");
		operations.deposit("admin", "fund", new MonetaryRequests.Deposit(alice, "10000"));
	}
	UUID create(String name, String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user(id, username, password_hash, role) VALUES (?, ?, '$argon2id$fixture', ?)", id, name, role);
		wallets.create(id);
		return jdbc.queryForObject("SELECT id FROM wallet WHERE owner_id = ?", UUID.class, id);
	}
	HoldResponse reserve(String key, boolean transfer) {
		return holds.create("alice", key, new CreateHoldRequest(transfer ? "TRANSFER" : "WITHDRAWAL", transfer ? bob : null, "1000", "10", 100));
	}
	long balance(String kind) { return jdbc.queryForObject("SELECT balance_minor FROM ledger_account WHERE wallet_id = ? AND kind = ?", Long.class, alice, kind); }
	void expiresIn(UUID id, int seconds) {
		jdbc.execute("ALTER TABLE payment_hold DISABLE TRIGGER guard_payment_hold");
		try { jdbc.update("UPDATE payment_hold SET created_at = clock_timestamp() - interval '16 minutes', expires_at = clock_timestamp() + make_interval(secs => ?) WHERE id = ?", seconds, id); }
		finally { jdbc.execute("ALTER TABLE payment_hold ENABLE TRIGGER guard_payment_hold"); }
	}
	static void code(Runnable action, String expected) {
		assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).code()).isEqualTo(expected);
	}

	@Test void reservesCapturesAndRefundsToAvailableWithoutReopeningHold() {
		var hold = reserve("reserve", true);
		assertThat(hold.expires_at()).isBetween(hold.created_at().plusSeconds(899), hold.created_at().plusSeconds(901));
		assertThat(balance("AVAILABLE")).isEqualTo(8990); assertThat(balance("RESERVED")).isEqualTo(1010);
		assertThat(holds.create("alice", "reserve", new CreateHoldRequest("TRANSFER", bob, "1000", "10", 100))).isEqualTo(hold);
		var captured = holds.finish("alice", "capture", hold.id(), true);
		assertThat(captured.state()).isEqualTo("CAPTURED"); assertThat(balance("RESERVED")).isZero();
		assertThat(holds.finish("alice", "capture", hold.id(), true)).isEqualTo(captured);
		operations.reverse("admin", "refund", captured.completion_operation_id());
		assertThat(balance("AVAILABLE")).isEqualTo(10000); assertThat(balance("RESERVED")).isZero();
		assertThat(holds.get("alice", hold.id()).state()).isEqualTo("CAPTURED");
		code(() -> holds.finish("alice", "late", hold.id(), false), "HOLD_CLOSED");
		code(() -> operations.reverse("admin", "bad-refund", hold.creation_operation_id()), "NOT_REVERSIBLE");
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void capturesWithdrawalWithStoredTermsAndCancelsWithoutFee() {
		var hold = reserve("withdrawal", false);
		var captured = holds.finish("alice", "capture", hold.id(), true);
		assertThat(captured.fee_minor()).isEqualTo("10");
		assertThat(jdbc.queryForObject("SELECT type FROM payment_operation WHERE id = ?", String.class, captured.completion_operation_id())).isEqualTo("CAPTURE_WITHDRAWAL");
		operations.reverse("admin", "refund", captured.completion_operation_id());
		var cancelled = holds.finish("alice", "cancel", reserve("second", false).id(), false);
		assertThat(cancelled.state()).isEqualTo("CANCELLED"); assertThat(balance("AVAILABLE")).isEqualTo(10000);
		assertThat(balance("RESERVED")).isZero(); assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void expiresBacklogAfterRestartIncludingDisabledOwnersAndNeverCapturesExpired() {
		var first = reserve("first", true); var second = reserve("second", false);
		expiresIn(first.id(), -1); expiresIn(second.id(), -1);
		code(() -> holds.finish("alice", "expired", first.id(), true), "HOLD_EXPIRED");
		jdbc.update("UPDATE app_user SET enabled = false WHERE username = 'alice'");
		new HoldExpirationJob(repository, holds).expireDue();
		new HoldExpirationJob(repository, holds).expireDue();
		assertThat(repository.find(first.id(), null, false, false).orElseThrow().state()).isEqualTo("EXPIRED");
		assertThat(balance("AVAILABLE")).isEqualTo(10000); assertThat(balance("RESERVED")).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_operation WHERE type = 'HOLD_EXPIRE'", Integer.class)).isEqualTo(2);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void closesExactlyOnceForConcurrentCaptureAndCancellation() throws Exception {
		var hold = reserve("race", true);
		var results = race(8, i -> {
			try { holds.finish("alice", "finish-" + i, hold.id(), i % 2 == 0); return true; }
			catch (ApiException e) { assertThat(e.code()).isEqualTo("HOLD_CLOSED"); return false; }
		});
		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(balance("RESERVED")).isZero(); assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void closesExactlyOnceForConcurrentExpirationAndCancellation() throws Exception {
		var hold = reserve("race", false); expiresIn(hold.id(), -1);
		var results = race(8, i -> {
			if (i % 2 == 0) return holds.expire(hold.id());
			try { return "EXPIRED".equals(holds.finish("alice", "cancel-" + i, hold.id(), false).state()); }
			catch (ApiException e) { assertThat(e.code()).isEqualTo("HOLD_CLOSED"); return false; }
		});
		assertThat(results).filteredOn(Boolean::booleanValue).hasSize(1);
		assertThat(balance("AVAILABLE")).isEqualTo(10000); assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void rechecksDeadlineAfterWaitingForAccountLock() throws Exception {
		var hold = reserve("wait", false);
		try (var connection = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
			connection.setAutoCommit(false);
			try (var statement = connection.prepareStatement("SELECT id FROM ledger_account WHERE wallet_id = ? AND kind = 'RESERVED' FOR UPDATE")) {
				statement.setObject(1, alice); statement.executeQuery().close();
			}
			expiresIn(hold.id(), 2);
			var future = executor.submit(() -> { code(() -> holds.finish("alice", "wait-capture", hold.id(), true), "HOLD_EXPIRED"); });
			try {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
				boolean blocked = false;
				while (System.nanoTime() < deadline) {
					if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%ORDER BY id FOR UPDATE%'", Integer.class) > 0) { blocked = true; break; }
					Thread.sleep(20);
				}
				assertThat(blocked).isTrue(); Thread.sleep(2200);
			} finally { connection.rollback(); }
			future.get(10, TimeUnit.SECONDS);
		}
		assertThat(holds.get("alice", hold.id()).state()).isEqualTo("ACTIVE");
		assertThat(holds.expire(hold.id())).isTrue(); assertThat(balance("AVAILABLE")).isEqualTo(10000);
	}

	@Test void rollsBackCaptureWhenPostingFailsAndProtectsOwnershipAndConstraints() throws Exception {
		var hold = reserve("held", true);
		jdbc.execute("CREATE FUNCTION reject_hold_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test failure'; END $$");
		jdbc.execute("CREATE TRIGGER reject_hold_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_hold_audit()");
		try { assertThatThrownBy(() -> holds.finish("alice", "retry", hold.id(), true)).isInstanceOf(org.springframework.dao.DataAccessException.class); }
		finally { jdbc.execute("DROP TRIGGER reject_hold_audit ON audit_event"); jdbc.execute("DROP FUNCTION reject_hold_audit()"); }
		assertThat(holds.get("alice", hold.id()).state()).isEqualTo("ACTIVE"); assertThat(balance("RESERVED")).isEqualTo(1010);
		code(() -> holds.finish("bob", "foreign", hold.id(), true), "HOLD_NOT_FOUND");
		mvc.perform(get("/api/v1/holds/" + hold.id()).with(user("bob"))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/holds/" + hold.id() + "/capture").with(user("alice"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/holds?limit=101").with(user("alice"))).andExpect(status().isBadRequest());
		assertThatThrownBy(() -> jdbc.update("UPDATE payment_hold SET amount_minor = 2 WHERE id = ?", hold.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
		holds.finish("alice", "retry", hold.id(), true);
		assertThatThrownBy(() -> jdbc.update("UPDATE payment_hold SET state = 'ACTIVE', completion_operation_id = NULL, completed_at = NULL WHERE id = ?", hold.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void preventsOverspendingAndCreatesOneHoldForConcurrentRetries() throws Exception {
		var results = race(6, i -> reserve("same", true));
		assertThat(results.stream().map(HoldResponse::id).distinct()).hasSize(1);
		code(() -> holds.create("alice", "too-much", new CreateHoldRequest("WITHDRAWAL", null, "10000", "100", 100)), "INSUFFICIENT_FUNDS");
		code(() -> holds.create("alice", "stale", new CreateHoldRequest("WITHDRAWAL", null, "1", "0", 100)), "QUOTE_CHANGED");
		assertThat(balance("RESERVED")).isEqualTo(1010); assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test void keepsCapacityForCancellationWhenAvailableAndReservedApproachLongLimit() {
		operations.deposit("admin", "max", new MonetaryRequests.Deposit(alice, Long.toString(Long.MAX_VALUE - 10000)));
		var hold = reserve("capacity", false);
		code(() -> operations.deposit("admin", "overflow", new MonetaryRequests.Deposit(alice, "1")), "BALANCE_OVERFLOW");
		holds.finish("alice", "release", hold.id(), false);
		assertThat(balance("AVAILABLE")).isEqualTo(Long.MAX_VALUE);
		assertThat(balance("RESERVED")).isZero();assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	<T> List<T> race(int size, Task<T> task) throws Exception {
		var barrier = new CyclicBarrier(size);
		try (var executor = Executors.newFixedThreadPool(size)) {
			var tasks = new ArrayList<Callable<T>>();
			for (int i = 0; i < size; i++) { int index = i; tasks.add(() -> { barrier.await(10, TimeUnit.SECONDS); return task.run(index); }); }
			var results = new ArrayList<T>();
			for (var future : executor.invokeAll(tasks, 35, TimeUnit.SECONDS)) results.add(future.get());
			return results;
		}
	}
	interface Task<T> { T run(int index) throws Exception; }
}
