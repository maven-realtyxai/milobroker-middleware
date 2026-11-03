package com.milobroker.atlas.service;

import com.milobroker.atlas.repository.CountryRepository;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Resolves a public contact destination from Atlas reference data. */
@Service @Transactional(readOnly = true)
public class ContactService {
  private final CountryRepository countries;

  public ContactService(CountryRepository countries) {
    this.countries = countries;
  }

  public ContactResponse contact(String countryIso2) {
    String country = countryIso2 == null ? "IN" : countryIso2.trim().toUpperCase(Locale.ROOT);
    if (!country.matches("[A-Z]{2}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Country code must be a two-letter ISO code");
    }
    var countryRow = countries.findByShortForm(country)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Country is not configured"));
    return new ContactResponse(countryRow.getShortForm(), countryRow.getWhatsappNumber());
  }

  /** Shape consumed by the existing public-site contact fetch. */
  public record ContactResponse(String countryCode, String whatsappPhoneNumber) {}
}
