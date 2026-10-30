package com.example.payments2.payment_core2.users;

import java.security.Principal;

import com.example.payments2.payment_core2.common.web.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/auth")
public class AuthController {

	private final AuthenticationManager authenticationManager;
	private final SessionAuthenticationStrategy sessions;
	private final SecurityContextRepository contexts;
	private final CsrfTokenRepository csrfTokens;
	private final LoginRateLimiter rateLimiter;
	private final UserService users;
	private final boolean secureCookie;

	public AuthController(AuthenticationManager authenticationManager, SessionAuthenticationStrategy sessions,
			SecurityContextRepository contexts, CsrfTokenRepository csrfTokens, LoginRateLimiter rateLimiter,
			UserService users, @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookie) {
		this.authenticationManager = authenticationManager;
		this.sessions = sessions;
		this.contexts = contexts;
		this.csrfTokens = csrfTokens;
		this.rateLimiter = rateLimiter;
		this.users = users;
		this.secureCookie = secureCookie;
	}

	@PostMapping("/login")
	public UserResponse login(@Valid @RequestBody LoginRequest input, HttpServletRequest request,
			HttpServletResponse response) {
		rateLimiter.acquire(input.username(), request.getRemoteAddr());
		Authentication authentication;
		try {
			authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
					UserRepository.normalizeUsername(input.username()), input.password()));
		} catch (AuthenticationException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid username or password.");
		}
		sessions.onAuthentication(authentication, request, response);
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		contexts.saveContext(context, request, response);
		return users.current(authentication.getName());
	}

	@GetMapping("/me")
	public UserResponse me(Principal principal) {
		return users.current(principal.getName());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		csrfTokens.saveToken(null, request, response);
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		var cookie = new Cookie("PAYMENT_SESSION", "");
		cookie.setPath(request.getContextPath().isEmpty() ? "/" : request.getContextPath());
		cookie.setMaxAge(0);
		cookie.setHttpOnly(true);
		cookie.setSecure(secureCookie);
		cookie.setAttribute("SameSite", "Lax");
		response.addCookie(cookie);
	}

	public record LoginRequest(
			@NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}") String username,
			@NotBlank @Size(max = 128) String password) {

		@Override
		public String toString() {
			return "LoginRequest[redacted]";
		}
	}
}
