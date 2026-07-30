package com.smartlink.infrastructure.config;

import com.smartlink.domain.CodeGenerator;
import com.smartlink.domain.DestinationPolicy;
import com.smartlink.domain.port.HostResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain types into the container.
 *
 * <p>The domain classes carry no Spring annotations, so their construction has to happen somewhere,
 * and it happens here rather than by making them components. That is the price of keeping {@code
 * domain} framework-free — and the reason the price is worth paying is that the destination policy
 * and the code generator, the two pieces with the most branching in the system, stay testable with
 * no context and no container.
 */
@Configuration
public class DomainConfig {

  @Bean
  public DestinationPolicy destinationPolicy(
      HostResolver hostResolver,
      @Value("${smartlink.destination.max-length:2048}") int maxDestinationLength) {
    return new DestinationPolicy(hostResolver, maxDestinationLength);
  }

  @Bean
  public CodeGenerator codeGenerator() {
    // Default constructor uses SecureRandom. An ordinary PRNG is predictable from a handful of
    // observed outputs, and a caller already has every code it has created.
    return new CodeGenerator();
  }
}
