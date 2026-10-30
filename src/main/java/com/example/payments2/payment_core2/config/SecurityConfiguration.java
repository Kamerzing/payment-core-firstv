package com.example.payments2.payment_core2.config;

import java.util.List;

import com.example.payments2.payment_core2.common.web.ApiError;
import com.example.payments2.payment_core2.common.web.ApiErrorWriter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter,
			Environment environment, CsrfTokenRepository csrfTokens, SecurityContextRepository contexts) throws Exception {
		boolean localDocumentation = environment.acceptsProfiles(Profiles.of("local", "test"))
				&& !environment.acceptsProfiles(Profiles.of("server"));
		http
			.authorizeHttpRequests(authorize -> {
				authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
				authorize.requestMatchers(HttpMethod.GET, "/api/v1/status", "/api/v1/auth/csrf",
						"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness").permitAll();
				authorize.requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**").permitAll();
				if (localDocumentation) {
					authorize.requestMatchers(HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
							"/swagger-ui.html", "/swagger-ui/**").permitAll();
				}
				authorize.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
				authorize.requestMatchers("/api/v1/admin/**").hasRole("ADMIN");
				authorize.anyRequest().authenticated();
			})
			.csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
					"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")))
			.securityContext(context -> context.securityContextRepository(contexts))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint((request, response, exception) ->
						errorWriter.write(response, ApiError.of(HttpStatus.UNAUTHORIZED, request)))
				.accessDeniedHandler((request, response, exception) -> errorWriter.write(response,
						exception instanceof CsrfException ? ApiError.csrf(request)
								: ApiError.of(HttpStatus.FORBIDDEN, request))));
		return http.build();
	}

	@Bean
	CsrfTokenRepository csrfTokenRepository() {
		return new HttpSessionCsrfTokenRepository();
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
				new HttpSessionSecurityContextRepository());
	}

	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokens) {
		return new CompositeSessionAuthenticationStrategy(List.of(new ChangeSessionIdAuthenticationStrategy(),
				new CsrfAuthenticationStrategy(csrfTokens)));
	}
}
