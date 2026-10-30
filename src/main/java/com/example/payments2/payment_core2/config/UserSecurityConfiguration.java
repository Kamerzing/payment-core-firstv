package com.example.payments2.payment_core2.config;

import com.example.payments2.payment_core2.users.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class UserSecurityConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}

	@Bean
	UserDetailsService userDetailsService(UserRepository users) {
		return username -> users.findCredentials(username).orElseThrow(() ->
				new UsernameNotFoundException("Invalid credentials."));
	}

	@Bean
	AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
		var provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(passwords);
		return new ProviderManager(provider);
	}
}
