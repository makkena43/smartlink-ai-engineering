package com.smartlink.application;

import com.smartlink.application.exception.LinkNotFoundException;
import com.smartlink.domain.ResolvedLink;
import com.smartlink.domain.ShortCode;
import com.smartlink.domain.port.LinkRepository;
import org.springframework.stereotype.Service;

/**
 * Reads the aggregate usage figures for a link.
 *
 * <p>Unauthenticated by design (GF-12). Possession of the code is the access control, which is
 * exactly why the code has to be unguessable — and why a malformed code returns the same answer as
 * an unknown one rather than a more helpful error.
 *
 * <p>Returns counters only. There is no per-request data to return because none is stored, and none
 * is stored because the schema has nowhere to put it (NFR-13).
 *
 * <p>Returns the link paired with the database's clock so the reported ACTIVE/EXPIRED status is
 * decided by the same authority as the redirect itself. Computing it from this instance's clock
 * would let analytics call a link active while the redirect path calls it expired.
 */
@Service
public class ReadAnalyticsUseCase {

  private final LinkRepository repository;

  public ReadAnalyticsUseCase(LinkRepository repository) {
    this.repository = repository;
  }

  public ResolvedLink read(String rawCode) {
    ShortCode code =
        ShortCode.parse(rawCode)
            .orElseThrow(() -> new LinkNotFoundException("code is not well-formed"));

    return repository.findByCode(code).orElseThrow(() -> new LinkNotFoundException("no link"));
  }
}
