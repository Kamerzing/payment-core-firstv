package com.example.payments2.payment_core2.wallets;

import java.util.UUID;

import com.example.payments2.payment_core2.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

	private final WalletRepository wallets;

	public WalletService(WalletRepository wallets) {
		this.wallets = wallets;
	}

	@Transactional(readOnly = true)
	public WalletResponse owned(String username, UUID walletId) {
		return wallets.findOwned(username, walletId).orElseThrow(() ->
				new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "The wallet was not found."));
	}
}
