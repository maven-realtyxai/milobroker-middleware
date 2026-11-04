package com.milobroker.concierge.service;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.OptionDto;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
/**
 * Onboarding flow logic. Dropdown options and semantic validation come from
 * Atlas; user records persist through the Marketplace API. Concierge keeps no
 * business tables: triage operations only.
 */
@Service
public class OnboardingService {
  private static final Map<String, String> INTENTS = Map.ofEntries(
    Map.entry("FIND A RENTAL", "RENTAL"),
    Map.entry("RENT", "RENTAL"),
    Map.entry("POST A PROPERTY", "LIST"),
    Map.entry("RENTAL", "RENTAL"),
    Map.entry("BUY", "BUY"),
    Map.entry("BUY A PROPERTY", "BUY"),
    Map.entry("PURCHASE", "BUY"),
    Map.entry("SALE", "BUY"),
    Map.entry("POST", "LIST"),
    Map.entry("LIST", "LIST"),
    Map.entry("SELL", "LIST"),
    Map.entry("AGENT", "AGENT"),
    Map.entry("BROKER", "AGENT"),
    Map.entry("I'M AN AGENT", "AGENT"),
    Map.entry("IM AN AGENT", "AGENT"));
  private final MarketplaceProfilesClient profiles;
  private final AtlasGeoClient atlas;
  public OnboardingService(MarketplaceProfilesClient profiles, AtlasGeoClient atlas) {
    this.profiles = profiles;
    this.atlas = atlas;
  }
  public List<OptionDto> states() {
    return atlas.states().stream()
      .map(s -> new OptionDto(s.code(), s.displayName())).toList();
  }
  /** Role options are served dynamically by the Flow endpoint. */
  public List<OptionDto> roles() {
    return List.of(
      new OptionDto("RENTAL", "Rent"),
      new OptionDto("BUY", "Buy"),
      new OptionDto("LIST", "Post a property"),
      new OptionDto("AGENT", "Agent"));
  }
  public List<OptionDto> cities(String stateCode) {
    String code = requireState(stateCode);
    return atlas.cities(code).stream()
      .map(c -> new OptionDto(c.code(), c.displayName())).toList();
  }
  public ProfileDto profile(String channelUserId) {
    if (channelUserId == null || channelUserId.isBlank()) {
      return new ProfileDto(channelUserId, null, null, null, null, false);
    }
    return profiles.fetch(channelUserId);
  }
  public boolean claimWelcome(String channelUserId, String displayName) {
    return profiles.claimWelcome(channelUserId, displayName);
  }
  public void releaseWelcome(String channelUserId) {
    profiles.releaseWelcome(channelUserId);
  }
  /** Validates against Atlas, then saves through Marketplace and marks complete. Idempotent. */
  public ProfileDto complete(String channelUserId, CompletionRequest request) {
    if (channelUserId == null || channelUserId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing user id");
    }
    if (request.displayName() == null || request.displayName().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name is required");
    }
    if (!Boolean.TRUE.equals(request.termsAccepted())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terms must be accepted");
    }
    String intent = normalizeIntent(request.intent());
    String stateCode = requireState(request.stateCode());
    String cityCode = requireCity(stateCode, request.cityCode());
    return profiles.save(channelUserId, request.displayName().trim(), intent, stateCode, cityCode, true);
  }
  private String normalizeIntent(String intent) {
    if (intent == null || intent.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Intent is required");
    }
    String key = intent.trim().replace('’', '\'').toUpperCase(Locale.ROOT);
    if (INTENTS.containsValue(key)) {
      return key;
    }
    String mapped = INTENTS.get(key);
    if (mapped == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown intent: " + intent);
    }
    return mapped;
  }
  private String requireState(String stateCode) {
    if (stateCode == null || stateCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "State is required");
    }
    String code = stateCode.trim().toUpperCase(Locale.ROOT);
    boolean known = atlas.states().stream().anyMatch(s -> s.code().equalsIgnoreCase(code));
    if (!known) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown state: " + stateCode);
    }
    return code;
  }
  private String requireCity(String stateCode, String cityCode) {
    if (cityCode == null || cityCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "City is required");
    }
    String code = cityCode.trim().toUpperCase(Locale.ROOT);
    boolean belongs = atlas.cities(stateCode).stream()
      .anyMatch(c -> c.code().equalsIgnoreCase(code));
    if (!belongs) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "City " + cityCode + " does not belong to state " + stateCode);
    }
    return code;
  }
}
