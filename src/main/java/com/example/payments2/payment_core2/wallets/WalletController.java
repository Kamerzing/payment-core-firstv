package com.example.payments2.payment_core2.wallets;

import java.security.Principal;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

	private final WalletService wallets;

	public WalletController(WalletService wallets) {
		this.wallets = wallets;
	}

	@GetMapping("/me")
	public WalletResponse mine(Principal principal) {
		return wallets.owned(principal.getName(), null);
	}

	@GetMapping("/{id}")
	public WalletResponse byId(@PathVariable UUID id, Principal principal) {
		return wallets.owned(principal.getName(), id);
	}
}
