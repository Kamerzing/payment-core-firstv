package com.example.payments2.payment_core2.users;

import java.util.List;

import com.example.payments2.payment_core2.common.web.ApiException;
import com.example.payments2.payment_core2.wallets.WalletRepository;
import jakarta.validation.Validator;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

	private final UserRepository users;
	private final WalletRepository wallets;
	private final PasswordEncoder passwords;
	private final Validator validator;

	public UserService(UserRepository users, WalletRepository wallets, PasswordEncoder passwords, Validator validator) {
		this.users = users;
		this.wallets = wallets;
		this.passwords = passwords;
		this.validator = validator;
	}

	@Transactional
	public UserResponse create(CreateUserRequest request) {
		return createWithRole(request, "USER");
	}

	@Transactional
	public UserResponse bootstrapAdmin(CreateUserRequest request) {
		if (users.hasAdminUnderBootstrapLock()) {
			throw new ApiException(HttpStatus.CONFLICT, "ADMIN_ALREADY_EXISTS", "An administrator already exists.");
		}
		return createWithRole(request, "ADMIN");
	}

	private UserResponse createWithRole(CreateUserRequest request, String role) {
		if (!validator.validate(request).isEmpty()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
		}
		UserResponse user;
		try {
			user = users.insert(request.username(), passwords.encode(request.password()), role);
		} catch (DuplicateKeyException exception) {
			throw new ApiException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "The username is already in use.");
		}
		wallets.create(user.id());
		return user;
	}

	@Transactional(readOnly = true)
	public List<UserResponse> list(int limit, int offset) {
		if (limit < 1 || limit > 100 || offset < 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request is invalid.");
		}
		return users.list(limit, offset);
	}

	@Transactional(readOnly = true)
	public UserResponse current(String username) {
		return users.findByUsername(username).orElseThrow(() ->
				new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication is required."));
	}
}
