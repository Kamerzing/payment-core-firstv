package com.example.payments2.payment_core2;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.ledger.LedgerRepository;
import com.example.payments2.payment_core2.ledger.ReconciliationService;
import com.example.payments2.payment_core2.operations.MonetaryRequests;
import com.example.payments2.payment_core2.operations.OperationResponse;
import com.example.payments2.payment_core2.operations.OperationService;
import com.example.payments2.payment_core2.operations.FeePolicy;
import com.example.payments2.payment_core2.operations.FinancialAccess;
import com.example.payments2.payment_core2.operations.IdempotencyService;
import com.example.payments2.payment_core2.operations.OperationRepository;
import com.example.payments2.payment_core2.ledger.LedgerService;
import com.example.payments2.payment_core2.audit.AuditService;
import com.example.payments2.payment_core2.wallets.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class LedgerOperationTests {
	@Autowired JdbcTemplate jdbc;
	@Autowired WalletRepository wallets;
	@Autowired OperationService operations;
	@Autowired ReconciliationService reconciliation;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper json;
	@Autowired ApplicationContext context;
	UUID alice;
	UUID bob;
	UUID charlie;
	UUID admin;

	@BeforeEach
	void initializeIsolatedDatabase() {
		jdbc.execute("TRUNCATE audit_event, command_result, ledger_posting, payment_operation, ledger_account, wallet, app_user, login_attempt_bucket, spring_session CASCADE");
		jdbc.update("INSERT INTO ledger_account (id, kind) VALUES (?, 'SETTLEMENT'), (?, 'FEES')", LedgerRepository.SETTLEMENT, LedgerRepository.FEES);
		admin = create("admin", "ADMIN");
		alice = create("alice", "USER");
		bob = create("bob", "USER");
		charlie = create("charlie", "USER");
	}

	private UUID create(String username, String role) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user (id, username, password_hash, role) VALUES (?, ?, '$argon2id$test-fixture', ?)", id, username, role);
		wallets.create(id);
		return jdbc.queryForObject("SELECT id FROM wallet WHERE owner_id = ?", UUID.class, id);
	}

	private OperationResponse deposit(UUID wallet, String amount, String key) {
		return operations.deposit("admin", key, new MonetaryRequests.Deposit(wallet, amount));
	}

	private MonetaryRequests.Transfer transfer(UUID target, String amount, String fee) {
		return new MonetaryRequests.Transfer(target, amount, fee, 100);
	}

	private long balance(UUID wallet, String kind) {
		return jdbc.queryForObject("SELECT balance_minor FROM ledger_account WHERE wallet_id = ? AND kind = ?", Long.class, wallet, kind);
	}

	private long count(String table) {
		return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
	}

	@Test
	void executesHttpDepositQuoteTransferWithdrawalAndFullRefund() throws Exception {
		mvc.perform(post("/api/v1/admin/deposits").with(user("admin").roles("ADMIN")).with(csrf())
				.header("Idempotency-Key", "initial").header("X-Request-ID", "deposit-test")
				.contentType("application/json").content(json.writeValueAsString(new MonetaryRequests.Deposit(alice, "100000"))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.amount_minor").value("100000"))
				.andExpect(jsonPath("$.fee_minor").value("0")).andExpect(jsonPath("$.state").value("POSTED"));
		mvc.perform(post("/api/v1/quotes").with(user("alice")).with(csrf()).contentType("application/json")
				.content("{\"type\":\"TRANSFER\",\"amount_minor\":\"10000\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.fee_minor").value("100"))
				.andExpect(jsonPath("$.fee_basis_points").value(100)).andExpect(jsonPath("$.total_minor").value("10100"));
		mvc.perform(post("/api/v1/transfers").with(user("alice")).with(csrf()).header("Idempotency-Key", "transfer")
				.contentType("application/json").content(json.writeValueAsString(transfer(bob, "10000", "100"))))
				.andExpect(status().isCreated());
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(89900);
		assertThat(balance(bob, "AVAILABLE")).isEqualTo(10000);
		var debit = mvc.perform(post("/api/v1/withdrawals").with(user("bob")).with(csrf()).header("Idempotency-Key", "withdraw")
				.contentType("application/json").content(json.writeValueAsString(new MonetaryRequests.Withdrawal("9900", "99", 100))))
				.andExpect(status().isCreated()).andReturn().getResponse();
		UUID withdrawal = UUID.fromString(json.readTree(debit.getContentAsString()).get("id").asText());
		assertThat(balance(bob, "AVAILABLE")).isEqualTo(1);
		mvc.perform(post("/api/v1/admin/reversals").with(user("admin").roles("ADMIN")).with(csrf()).header("Idempotency-Key", "refund")
				.contentType("application/json").content(json.writeValueAsString(new MonetaryRequests.Reversal(withdrawal))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.fee_minor").value("99"));
		assertThat(balance(bob, "AVAILABLE")).isEqualTo(10000);
		assertThat(jdbc.queryForObject("SELECT balance_minor FROM ledger_account WHERE kind = 'FEES'", Long.class)).isEqualTo(100);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
		assertThat(count("audit_event")).isEqualTo(4);
		assertThat(jdbc.queryForObject("SELECT request_id FROM audit_event WHERE action = 'DEPOSIT'", String.class)).isEqualTo("deposit-test");
	}

	@Test
	void returnsSameHttpResultAndRejectsKeyReuseForChangedRequestOrAction() throws Exception {
		String body = json.writeValueAsString(new MonetaryRequests.Deposit(alice, "1000"));
		var first = mvc.perform(post("/api/v1/admin/deposits").with(user("admin").roles("ADMIN")).with(csrf())
				.header("Idempotency-Key", "same-key").contentType("application/json").content(body))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		mvc.perform(post("/api/v1/admin/deposits").with(user("admin").roles("ADMIN")).with(csrf())
				.header("Idempotency-Key", "same-key").contentType("application/json").content(body))
				.andExpect(status().isCreated()).andExpect(content().json(first));
		assertCode(() -> deposit(alice, "1001", "same-key"), "IDEMPOTENCY_CONFLICT");
		assertCode(() -> deposit(bob, "1000", "same-key"), "IDEMPOTENCY_CONFLICT");
		UUID original = UUID.fromString(json.readTree(first).get("id").asText());
		assertCode(() -> operations.reverse("admin", "same-key", original), "IDEMPOTENCY_CONFLICT");
		assertThat(count("payment_operation")).isEqualTo(1);
		assertThat(count("audit_event")).isEqualTo(1);
	}

	@Test
	void replaysSavedResultAndRefundsOriginalFeeAfterPricingChanges() {
		deposit(alice, "1010", "fund");
		var input = transfer(bob, "1000", "10");
		var original = operations.transfer("alice", "old-price", input);
		var repriced = new OperationService(context.getBean(FinancialAccess.class), new FeePolicy(500),
				context.getBean(IdempotencyService.class), context.getBean(LedgerService.class),
				context.getBean(LedgerRepository.class), context.getBean(OperationRepository.class), context.getBean(AuditService.class));
		var tx = new TransactionTemplate(transactionManager);
		OperationResponse replay = tx.execute(status -> repriced.transfer("alice", "old-price", input));
		assertThat(replay).isEqualTo(original);
		assertCode(() -> tx.execute(status -> repriced.transfer("alice", "new-price", input)), "QUOTE_CHANGED");
		var refund = tx.execute(status -> repriced.reverse("admin", "refund-old-price", original.id()));
		assertThat(refund.fee_minor()).isEqualTo("10");
		assertThat(refund.fee_basis_points()).isEqualTo(100);
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(1010);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void postsOnlyOnceForConcurrentRetriesAndScopesKeysToActor() throws Exception {
		var results = concurrent(8, i -> deposit(alice, "1000", "parallel"));
		assertThat(results.stream().map(OperationResponse::id).distinct()).hasSize(1);
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(1000);
		assertThat(count("payment_operation")).isEqualTo(1);
		operations.transfer("alice", "parallel", transfer(bob, "100", "1"));
		assertThat(count("command_result")).isEqualTo(2);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void rollsBackBusinessFailuresAndAllowsSameKeyAfterFunding() {
		assertCode(() -> operations.transfer("alice", "retry", transfer(bob, "100", "1")), "INSUFFICIENT_FUNDS");
		assertThat(count("command_result")).isZero();
		assertThat(count("payment_operation")).isZero();
		deposit(alice, "101", "fund");
		operations.transfer("alice", "retry", transfer(bob, "100", "1"));
		assertThat(balance(alice, "AVAILABLE")).isZero();
		assertCode(() -> operations.transfer("bob", "stale", transfer(alice, "1", "0")), "QUOTE_CHANGED");
		assertCode(() -> operations.withdraw("bob", "stale-rate", new MonetaryRequests.Withdrawal("1", "1", 101)), "QUOTE_CHANGED");
		assertCode(() -> operations.transfer("bob", "self", transfer(bob, "1", "1")), "SELF_TRANSFER");
		assertThat(count("command_result")).isEqualTo(2);
	}

	@Test
	void preventsConcurrentOverspendingAndDeadlocksForOppositeTransfers() throws Exception {
		deposit(alice, "101", "fund");
		var spends = concurrent(8, i -> {
			try {
				operations.transfer("alice", "spend-" + i, transfer(bob, "100", "1"));
				return true;
			} catch (ApiException e) {
				assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS");
				return false;
			}
		});
		assertThat(spends).filteredOn(Boolean::booleanValue).hasSize(1);
		deposit(alice, "1000", "more-alice");
		deposit(bob, "1000", "more-bob");
		concurrent(8, i -> operations.transfer(i % 2 == 0 ? "alice" : "bob", "opposite-" + i,
				transfer(i % 2 == 0 ? bob : alice, "10", "1")));
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void reversesFullTransferOnceAndRejectsRefundOfRefund() throws Exception {
		deposit(alice, "1010", "fund");
		var original = operations.transfer("alice", "send", transfer(bob, "1000", "10"));
		var results = concurrent(6, i -> {
			try {
				return operations.reverse("admin", "reverse-" + i, original.id());
			} catch (ApiException e) {
				assertThat(e.code()).isEqualTo("ALREADY_REVERSED");
				return null;
			}
		});
		assertThat(results).filteredOn(r -> r != null).hasSize(1);
		var refund = results.stream().filter(r -> r != null).findFirst().orElseThrow();
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(1010);
		assertThat(balance(bob, "AVAILABLE")).isZero();
		assertCode(() -> operations.reverse("admin", "recursive", refund.id()), "NOT_REVERSIBLE");
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void rollsBackRefundIfRecipientSpentFundsAndCanReverseDeposit() {
		var initial = deposit(alice, "1010", "fund");
		var transfer = operations.transfer("alice", "send", transfer(bob, "1000", "10"));
		operations.withdraw("bob", "spend", new MonetaryRequests.Withdrawal("900", "9", 100));
		assertCode(() -> operations.reverse("admin", "refund", transfer.id()), "INSUFFICIENT_FUNDS");
		assertThat(balance(alice, "AVAILABLE")).isZero();
		assertThat(balance(bob, "AVAILABLE")).isEqualTo(91);
		assertCode(() -> operations.reverse("admin", "refund-deposit", initial.id()), "INSUFFICIENT_FUNDS");
		deposit(bob, "909", "restore");
		operations.reverse("admin", "refund", transfer.id());
		operations.reverse("admin", "refund-deposit", initial.id());
		assertThat(balance(alice, "AVAILABLE")).isZero();
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void rejectsAccountAndSettlementOverflowWithoutPartialWrites() {
		deposit(alice, Long.toString(Long.MAX_VALUE), "max");
		assertCode(() -> deposit(alice, "1", "overflow"), "BALANCE_OVERFLOW");
		deposit(bob, "1", "last-kopeck");
		assertCode(() -> deposit(charlie, "1", "settlement-overflow"), "BALANCE_OVERFLOW");
		assertThat(count("payment_operation")).isEqualTo(2);
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(Long.MAX_VALUE);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void rollsBackPostingsBalancesAuditAndIdempotencyOnDatabaseFailure() {
		deposit(alice, "1000", "fund");
		jdbc.execute("""
				CREATE FUNCTION test_fail_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
				RAISE EXCEPTION 'injected failure after posting'; END $$
				""");
		jdbc.execute("CREATE TRIGGER test_fail_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION test_fail_audit()");
		try {
			assertThatThrownBy(() -> operations.transfer("alice", "failing", transfer(bob, "100", "1"))).isInstanceOf(DataAccessException.class);
			assertThat(balance(alice, "AVAILABLE")).isEqualTo(1000);
			assertThat(balance(bob, "AVAILABLE")).isZero();
			assertThat(count("payment_operation")).isEqualTo(1);
			assertThat(count("ledger_posting")).isEqualTo(2);
			assertThat(count("audit_event")).isEqualTo(1);
			assertThat(count("command_result")).isEqualTo(1);
		} finally {
			jdbc.execute("DROP TRIGGER test_fail_audit ON audit_event");
			jdbc.execute("DROP FUNCTION test_fail_audit()");
		}
		operations.transfer("alice", "failing", transfer(bob, "100", "1"));
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void databaseRejectsUnbalancedUnpostedMutableAndLatePostingsAndUnexplainedBalances() {
		var operation = deposit(alice, "1000", "fund");
		for (String sql : List.of(
				"UPDATE ledger_posting SET amount_minor = 2 WHERE operation_id = '" + operation.id() + "'",
				"DELETE FROM ledger_posting WHERE operation_id = '" + operation.id() + "'",
				"UPDATE payment_operation SET amount_minor = 2 WHERE id = '" + operation.id() + "'",
				"DELETE FROM payment_operation WHERE id = '" + operation.id() + "'",
				"UPDATE ledger_account SET balance_minor = 999 WHERE wallet_id = '" + alice + "' AND kind = 'AVAILABLE'",
				"UPDATE audit_event SET action = 'edited'")) {
			assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(DataAccessException.class);
		}
		UUID available = account(alice);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO ledger_posting (id, operation_id, account_id, amount_minor) VALUES (?, ?, ?, 1)",
				UUID.randomUUID(), operation.id(), available)).isInstanceOf(DataAccessException.class);
		long before = count("payment_operation");
		for (boolean post : List.of(false, true)) {
			assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				UUID draft = UUID.randomUUID();
				UUID actor = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'admin'", UUID.class);
				jdbc.update("INSERT INTO payment_operation (id, actor_id, type, target_wallet_id, amount_minor, fee_minor, fee_basis_points) VALUES (?, ?, 'DEPOSIT', ?, 1, 0, 0)", draft, actor, alice);
				jdbc.update("INSERT INTO ledger_posting (id, operation_id, account_id, amount_minor) VALUES (?, ?, ?, 1)", UUID.randomUUID(), draft, available);
				if (!post) jdbc.update("INSERT INTO ledger_posting (id, operation_id, account_id, amount_minor) VALUES (?, ?, ?, -1)",
						UUID.randomUUID(), draft, LedgerRepository.SETTLEMENT);
				if (post) jdbc.update("UPDATE payment_operation SET state = 'POSTED', posted_at = clock_timestamp() WHERE id = ?", draft);
			})).isInstanceOf(RuntimeException.class);
		}
		assertThat(count("payment_operation")).isEqualTo(before);
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(1000);
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void reconcilesCommittedSnapshotDuringConcurrentCommandsAndDetectsCorruption() throws Exception {
		deposit(alice, "10000", "fund");
		concurrent(8, i -> {
			for (int j = 0; j < 8; j++) {
				if (i % 2 == 0) operations.transfer("alice", "snapshot-" + i + "-" + j, transfer(bob, "1", "1"));
				else assertThat(reconciliation.reconcile().consistent()).isTrue();
			}
			return true;
		});
		// Simulates storage corruption with owner privileges in this isolated container only.
		jdbc.execute("ALTER TABLE ledger_account DISABLE TRIGGER check_account_balance");
		try {
			jdbc.update("UPDATE ledger_account SET balance_minor = balance_minor + 1 WHERE id = ?", account(alice));
		} finally {
			jdbc.execute("ALTER TABLE ledger_account ENABLE TRIGGER check_account_balance");
		}
		var report = reconciliation.reconcile();
		assertThat(report.consistent()).isFalse();
		assertThat(report.accounts()).hasSize(1);
		assertThat(report.total_minor()).isEqualTo("1");
	}

	@Test
	void rejectsFractionalAndCoercedFeeRatesWithoutChangingMoney() throws Exception {
		deposit(alice, "1000", "fund");
		for (String rate : List.of("100.9", "1e2", "\"100\"", "2147483648", "null", "true", "-1", "10001")) {
			for (String path : List.of("/withdrawals", "/transfers", "/holds")) {
				mvc.perform(post("/api/v1" + path).with(user("alice")).with(csrf())
						.header("Idempotency-Key", "strict-rate").contentType("application/json")
						.content("{\"type\":\"TRANSFER\",\"target_wallet_id\":\"" + bob
								+ "\",\"amount_minor\":\"1\",\"expected_fee_minor\":\"1\",\"expected_fee_basis_points\":" + rate + "}"))
						.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
			}
		}
		assertThat(balance(alice, "AVAILABLE")).isEqualTo(1000);
		assertThat(balance(alice, "RESERVED")).isZero();
		assertThat(count("payment_operation")).isEqualTo(1);
		assertThat(count("command_result")).isEqualTo(1);
		assertThat(count("payment_hold")).isZero();
		assertThat(reconciliation.reconcile().consistent()).isTrue();
	}

	@Test
	void enforcesRolesOwnershipPaginationCsrfAndStrictStringAmounts() throws Exception {
		var initial = deposit(alice, "1000", "fund");
		mvc.perform(post("/api/v1/admin/deposits").with(user("alice")).with(csrf()).contentType("application/json").content("{}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/transfers").with(user("alice")).contentType("application/json").content("{}"))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
		mvc.perform(get("/api/v1/operations/" + initial.id()).with(user("bob"))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/operations/" + UUID.randomUUID()).with(user("bob"))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/operations").with(user("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		mvc.perform(get("/api/v1/operations").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get("/api/v1/admin/operations").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		UUID aliceId = jdbc.queryForObject("SELECT owner_id FROM wallet WHERE id = ?", UUID.class, alice);
		mvc.perform(get("/api/v1/admin/wallets?owner_id=" + aliceId).with(user("admin").roles("ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(alice.toString())).andExpect(jsonPath("$.items[0].available_minor").value("1000"));
		mvc.perform(get("/api/v1/admin/wallets").with(user("alice"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/reconciliation").with(user("alice"))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/reconciliation").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.consistent").value(true));
		mvc.perform(get("/api/v1/admin/audit?limit=1&offset=1").with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get("/api/v1/operations?limit=101").with(user("alice"))).andExpect(status().isBadRequest());
		for (String amount : List.of("123", "1.1", "null", "\"0\"", "\"01\"", "\"1.01\"", "\"-1\"", "\"9223372036854775808\"")) {
			mvc.perform(post("/api/v1/withdrawals").with(user("alice")).with(csrf()).header("Idempotency-Key", "invalid")
					.contentType("application/json").content("{\"amount_minor\":" + amount + ",\"expected_fee_minor\":\"1\",\"expected_fee_basis_points\":100}"))
					.andExpect(status().isBadRequest());
		}
		for (String key : List.of("", "bad key", "x".repeat(129))) {
			assertCode(() -> operations.withdraw("alice", key, new MonetaryRequests.Withdrawal("1", "1", 100)), "INVALID_IDEMPOTENCY_KEY");
		}
		mvc.perform(post("/api/v1/withdrawals").with(user("alice")).with(csrf()).contentType("application/json")
				.content("{\"amount_minor\":\"1\",\"expected_fee_minor\":\"1\",\"expected_fee_basis_points\":100}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
		mvc.perform(post("/api/v1/withdrawals").with(user("alice")).with(csrf()).header("Idempotency-Key", "one", "two")
				.contentType("application/json").content("{\"amount_minor\":\"1\",\"expected_fee_minor\":\"1\",\"expected_fee_basis_points\":100}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
		jdbc.update("UPDATE app_user SET enabled = false WHERE username = 'alice'");
		assertCode(() -> operations.withdraw("alice", "disabled", new MonetaryRequests.Withdrawal("1", "1", 100)), "UNAUTHORIZED");
	}

	private UUID account(UUID wallet) {
		return jdbc.queryForObject("SELECT id FROM ledger_account WHERE wallet_id = ? AND kind = 'AVAILABLE'", UUID.class, wallet);
	}

	private static void assertCode(Runnable action, String code) {
		assertThatThrownBy(action::run).isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).code()).isEqualTo(code);
	}

	private <T> List<T> concurrent(int size, IndexedCommand<T> command) throws Exception {
		var barrier = new CyclicBarrier(size);
		try (var executor = Executors.newFixedThreadPool(size)) {
			var tasks = new ArrayList<Callable<T>>();
			for (int i = 0; i < size; i++) {
				int index = i;
				tasks.add(() -> { barrier.await(10, TimeUnit.SECONDS); return command.run(index); });
			}
			var result = new ArrayList<T>();
			for (var future : executor.invokeAll(tasks, 40, TimeUnit.SECONDS)) result.add(future.get());
			return result;
		}
	}

	@FunctionalInterface
	private interface IndexedCommand<T> {
		T run(int index) throws Exception;
	}
}
