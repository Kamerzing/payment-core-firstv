package com.example.payments2.payment_core2.users;

import java.security.Principal;
import java.util.List;

import com.example.payments2.payment_core2.operations.FinancialAccess;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {

	private final UserService users;
	private final FinancialAccess access;

	public AdminUserController(UserService users, FinancialAccess access) {
		this.users = users;
		this.access = access;
	}

	@PostMapping
	public ResponseEntity<UserResponse> create(Principal principal, @Valid @RequestBody CreateUserRequest request) {
		access.actor(principal.getName(), true);
		UserResponse user = users.create(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(user);
	}

	@GetMapping
	public UserPage list(Principal principal, @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
		access.actor(principal.getName(), true);
		return new UserPage(users.list(limit, offset), limit, offset);
	}

	public record UserPage(List<UserResponse> items, int limit, int offset) {
	}
}
