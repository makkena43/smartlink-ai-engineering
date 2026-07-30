package com.smartlink.infrastructure.time;

import com.smartlink.domain.port.TimeSource;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The wall clock, in UTC.
 *
 * <p>Delegates to {@link Clock#systemUTC()} rather than calling {@code Instant.now()} directly, so
 * a deployment could substitute a synchronised or offset clock without touching the domain.
 *
 * <p>This is the one place the current time enters the system. Every expiry decision flows through
 * it, which is what makes the boundary testable and what stops "now" being read from a different
 * clock in each class that happens to need it.
 */
@Component
public class SystemTimeSource implements TimeSource {

  private final Clock clock = Clock.systemUTC();

  @Override
  public Instant now() {
    return clock.instant();
  }
}
