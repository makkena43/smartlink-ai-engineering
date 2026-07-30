package com.smartlink.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Attaches a correlation identifier to every request, response and log line.
 *
 * <p>This is what resolves the tension inside NFR-04. Errors must be actionable, and errors must
 * not disclose internals — but the detail that makes a failure diagnosable is usually the detail
 * that must not be published. A correlation ID splits the difference: the caller gets an opaque
 * handle, the operator joins it to internal logs, and nothing about the implementation crosses the
 * boundary.
 *
 * <p><strong>The inbound value is untrusted and is validated, not merely accepted.</strong> It is
 * echoed into a response header and written into logs, which makes an unchecked value two attacks
 * at once:
 *
 * <ul>
 *   <li><em>Response header injection</em> — a value containing CR or LF can terminate the header
 *       and inject others, the same primitive that makes the {@code Location} header dangerous
 *       (GF-18).
 *   <li><em>Log injection</em> — newlines let an attacker forge whole log entries, which is
 *       precisely as bad as it sounds when the logs are the evidence used during an incident.
 * </ul>
 *
 * <p>Anything not matching a conservative allowlist is discarded and replaced with a generated ID,
 * rather than sanitised in place. Sanitising invites a bypass; replacement does not.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";
  public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName();

  /**
   * Conservative allowlist: the character set of a UUID, a ULID, or a trace ID, and nothing else.
   */
  private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String correlationId = resolve(request.getHeader(HEADER));

    MDC.put(MDC_KEY, correlationId);
    request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
    response.setHeader(HEADER, correlationId);

    try {
      chain.doFilter(request, response);
    } finally {
      // Threads are pooled and reused. Leaving the value behind would stamp the next request
      // on this thread with the previous request's identifier, which is worse than having no
      // correlation ID at all: the logs would be confidently wrong.
      MDC.remove(MDC_KEY);
    }
  }

  private String resolve(String supplied) {
    if (supplied != null && ACCEPTABLE.matcher(supplied).matches()) {
      return supplied;
    }
    return UUID.randomUUID().toString();
  }

  /** Correlation ID for the request in flight, for use when building an error response. */
  public static String currentId(HttpServletRequest request) {
    Object value = request.getAttribute(REQUEST_ATTRIBUTE);
    return value instanceof String id ? id : "unknown";
  }
}
