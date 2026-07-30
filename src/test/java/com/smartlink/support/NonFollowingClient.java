package com.smartlink.support;

import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * An HTTP client that does <strong>not</strong> follow redirects and does not throw on 4xx/5xx.
 *
 * <p>Exists because {@code TestRestTemplate} follows redirects, which quietly makes every redirect
 * assertion test the wrong thing: the status observed is the destination site's, not this
 * service's, and {@code Location} has already been consumed by the client.
 *
 * <p>That is not a hypothetical. Written against {@code TestRestTemplate}, the header-injection
 * test failed with {@code IllegalArgumentException: Illegal character in path} — the client trying
 * to parse the CRLF payload so it could go and fetch it. A test that appears to assert on a
 * redirect while actually asserting on its target is worse than no test, because it reports green.
 */
public final class NonFollowingClient {

  private NonFollowingClient() {}

  public static RestTemplate create() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setOutputStreaming(false);

    RestTemplate template = new RestTemplate(factory);
    // 4xx and 5xx are outcomes under test here, not accidents worth throwing over.
    template.setErrorHandler(
        new DefaultResponseErrorHandler() {
          @Override
          public boolean hasError(ClientHttpResponse response) {
            return false;
          }
        });
    return template;
  }
}
