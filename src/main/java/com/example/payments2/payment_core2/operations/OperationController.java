package com.example.payments2.payment_core2.operations;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.example.payments2.payment_core2.audit.AuditService;
import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.ledger.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class OperationController {
	private final OperationService operations;
	private final FeePolicy fees;
	private final FinancialAccess access;
	private final ReconciliationService reconciliation;
	private final AuditService audit;

	public OperationController(OperationService operations, FeePolicy fees, FinancialAccess access,
			ReconciliationService reconciliation, AuditService audit) {
		this.operations = operations;
		this.fees = fees;
		this.access = access;
		this.reconciliation = reconciliation;
		this.audit = audit;
	}

	@PostMapping("/quotes")
	public FeePolicy.QuoteResponse quote(Principal principal, @Valid @RequestBody MonetaryRequests.Quote input) {
		access.actor(principal.getName(), false);
		return fees.quote(input);
	}

	@PostMapping("/admin/deposits")
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	@ResponseStatus(HttpStatus.CREATED)
	public OperationResponse deposit(Principal principal, HttpServletRequest request, @Valid @RequestBody MonetaryRequests.Deposit input) {
		return operations.deposit(principal.getName(), key(request), input);
	}

	@PostMapping("/transfers")
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	@ResponseStatus(HttpStatus.CREATED)
	public OperationResponse transfer(Principal principal, HttpServletRequest request, @Valid @RequestBody MonetaryRequests.Transfer input) {
		return operations.transfer(principal.getName(), key(request), input);
	}

	@PostMapping("/withdrawals")
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	@ResponseStatus(HttpStatus.CREATED)
	public OperationResponse withdraw(Principal principal, HttpServletRequest request, @Valid @RequestBody MonetaryRequests.Withdrawal input) {
		return operations.withdraw(principal.getName(), key(request), input);
	}

	@PostMapping("/admin/reversals")
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	@ResponseStatus(HttpStatus.CREATED)
	public OperationResponse reverse(Principal principal, HttpServletRequest request, @Valid @RequestBody MonetaryRequests.Reversal input) {
		return operations.reverse(principal.getName(), key(request), input.operation_id());
	}

	@GetMapping({"/operations", "/admin/operations"})
	public Page<OperationResponse> list(Principal principal, HttpServletRequest request,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
		return new Page<>(operations.list(principal.getName(), adminRoute(request), limit, offset), limit, offset);
	}

	@GetMapping({"/operations/{id}", "/admin/operations/{id}"})
	public OperationResponse get(Principal principal, HttpServletRequest request, @PathVariable UUID id) {
		return operations.get(principal.getName(), id, adminRoute(request));
	}

	@GetMapping("/admin/reconciliation")
	public ReconciliationService.Report reconcile(Principal principal) {
		access.actor(principal.getName(), true);
		return reconciliation.reconcile();
	}

	@GetMapping("/admin/audit")
	public Page<AuditService.Event> audit(Principal principal, @RequestParam(defaultValue = "20") int limit,
			@RequestParam(defaultValue = "0") int offset) {
		access.actor(principal.getName(), true);
		return new Page<>(audit.list(limit, offset), limit, offset);
	}

	public static String key(HttpServletRequest request) {
		var values = Collections.list(request.getHeaders("Idempotency-Key"));
		if (values.size() != 1) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "A single Idempotency-Key is required.");
		return values.getFirst();
	}

	private static boolean adminRoute(HttpServletRequest request) {
		return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/v1/admin/");
	}

	public record Page<T>(List<T> items, int limit, int offset) {
	}
}
