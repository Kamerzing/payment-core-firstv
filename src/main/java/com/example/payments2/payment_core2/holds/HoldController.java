package com.example.payments2.payment_core2.holds;

import java.security.Principal;
import java.util.UUID;
import com.example.payments2.payment_core2.operations.OperationController;
import com.example.payments2.payment_core2.operations.OperationController.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {
	private final HoldService holds;
	public HoldController(HoldService holds) { this.holds = holds; }

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	public HoldResponse create(Principal principal, HttpServletRequest request, @Valid @RequestBody CreateHoldRequest input) {
		return holds.create(principal.getName(), OperationController.key(request), input);
	}

	@PostMapping("/{id}/capture")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	public HoldResponse capture(Principal principal, HttpServletRequest request, @PathVariable UUID id) {
		return holds.finish(principal.getName(), OperationController.key(request), id, true);
	}

	@PostMapping("/{id}/cancel")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true))
	public HoldResponse cancel(Principal principal, HttpServletRequest request, @PathVariable UUID id) {
		return holds.finish(principal.getName(), OperationController.key(request), id, false);
	}

	@GetMapping
	public Page<HoldResponse> list(Principal principal, @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
		return new Page<>(holds.list(principal.getName(), limit, offset), limit, offset);
	}

	@GetMapping("/{id}")
	public HoldResponse get(Principal principal, @PathVariable UUID id) { return holds.get(principal.getName(), id); }
}
