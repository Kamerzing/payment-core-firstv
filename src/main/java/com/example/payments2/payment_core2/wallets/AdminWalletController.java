package com.example.payments2.payment_core2.wallets;

import java.security.Principal;
import java.util.UUID;

import com.example.payments2.payment_core2.operations.FinancialAccess;
import com.example.payments2.payment_core2.operations.Money;
import com.example.payments2.payment_core2.operations.OperationController.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/wallets")
public class AdminWalletController {
	private final FinancialAccess access;
	private final WalletRepository wallets;

	public AdminWalletController(FinancialAccess access, WalletRepository wallets) {
		this.access = access;
		this.wallets = wallets;
	}

	@GetMapping
	public Page<AdminWalletResponse> list(Principal principal, @RequestParam(required = false) UUID owner_id,
			@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
		access.actor(principal.getName(), true);
		if (limit < 1 || limit > 100 || offset < 0) throw Money.invalid();
		return new Page<>(wallets.list(owner_id, limit, offset), limit, offset);
	}
}
