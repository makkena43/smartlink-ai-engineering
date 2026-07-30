package com.smartlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SmartLink — a URL shortener built with spec-driven, AI-assisted engineering.
 *
 * <p>Layering is enforced by package, and the dependency rule runs inward only:
 *
 * <pre>
 *   api ──▶ application ──▶ domain ◀── infrastructure
 * </pre>
 *
 * <ul>
 *   <li>{@code domain} — entities and invariants. Depends on nothing.
 *   <li>{@code application} — use cases and orchestration. Depends on domain ports.
 *   <li>{@code infrastructure} — adapters: persistence, config, external concerns.
 *   <li>{@code api} — HTTP surface. Translates transport to use cases and back.
 * </ul>
 *
 * <p>The point of the rule is that the shortening and validation logic can be tested with no Spring
 * context and no database, which is what keeps the unit suite fast enough to run on every save.
 */
@SpringBootApplication
public class SmartLinkApplication {

  public static void main(String[] args) {
    SpringApplication.run(SmartLinkApplication.class, args);
  }
}
