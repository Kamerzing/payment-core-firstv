package com.example.payments2.payment_core2.common.web;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-ID";
	public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
	private static final String MDC_KEY = "request_id";
	private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		String requestId = (String) request.getAttribute(ATTRIBUTE);
		if (requestId == null) {
			var headers = Collections.list(request.getHeaders(HEADER));
			requestId = headers.size() == 1 && SAFE_ID.matcher(headers.getFirst()).matches()
					? headers.getFirst() : UUID.randomUUID().toString();
			request.setAttribute(ATTRIBUTE, requestId);
		}
		response.setHeader(HEADER, requestId);
		String previous = MDC.get(MDC_KEY);
		MDC.put(MDC_KEY, requestId);
		try {
			filterChain.doFilter(request, response);
		} finally {
			if (previous == null) {
				MDC.remove(MDC_KEY);
			} else {
				MDC.put(MDC_KEY, previous);
			}
		}
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}
}
