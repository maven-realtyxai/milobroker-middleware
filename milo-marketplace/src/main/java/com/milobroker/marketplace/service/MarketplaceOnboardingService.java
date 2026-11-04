package com.milobroker.marketplace.service;
import com.milobroker.marketplace.domain.OnboardingProfile;
import com.milobroker.marketplace.repository.OnboardingProfileRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
/**
 * User-record operations. Structural validation only (fields present);
 * semantic checks against Atlas stay with the Concierge caller.
 */
@Service @Transactional
public class MarketplaceOnboardingService {
  private final OnboardingProfileRepository profiles;
  public MarketplaceOnboardingService(OnboardingProfileRepository profiles) {
    this.profiles = profiles;
  }
  @Transactional(readOnly = true)
  public ProfileRecord fetch(String channelUserId) {
    requireId(channelUserId);
    return profiles.findById(channelUserId).map(MarketplaceOnboardingService::toRecord)
      .orElseGet(() -> new ProfileRecord(channelUserId, null, null, null, null, false));
  }
  /** Upserts the user record and marks onboarding complete. Idempotent. */
  public ProfileRecord save(String channelUserId, SaveProfileRequest request) {
    requireId(channelUserId);
    requirePresent(request.displayName(), "displayName");
    requirePresent(request.intent(), "intent");
    requirePresent(request.stateCode(), "stateCode");
    requirePresent(request.cityCode(), "cityCode");
    if (!Boolean.TRUE.equals(request.termsAccepted())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Terms must be accepted");
    }
    OnboardingProfile profile = profiles.findById(channelUserId)
      .orElseGet(() -> new OnboardingProfile(channelUserId));
    profile.setDisplayName(request.displayName().trim());
    profile.setIntent(request.intent().trim());
    profile.setStateCode(request.stateCode().trim());
    profile.setCityCode(request.cityCode().trim());
    if (profile.getTermsAcceptedAt() == null) {
      profile.setTermsAcceptedAt(Instant.now());
    }
    profile.setCompleted(true);
    profiles.save(profile);
    return toRecord(profile);
  }
  /** Returns true for exactly one first-contact welcome attempt per user. */
  public boolean claimWelcome(String channelUserId, String displayName) {
    requireId(channelUserId);
    String safeName = displayName == null || displayName.isBlank() ? null : displayName.trim();
    return profiles.claimWelcome(channelUserId, safeName) == 1;
  }
  public void releaseWelcome(String channelUserId) {
    requireId(channelUserId);
    profiles.releaseWelcome(channelUserId);
  }
  private static ProfileRecord toRecord(OnboardingProfile profile) {
    return new ProfileRecord(profile.getChannelUserId(), profile.getDisplayName(),
      profile.getIntent(), profile.getStateCode(), profile.getCityCode(), profile.isCompleted());
  }
  private static void requireId(String channelUserId) {
    if (channelUserId == null || channelUserId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelUserId is required");
    }
  }
  private static void requirePresent(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
  }
  public record ProfileRecord(String channelUserId, String displayName, String intent,
      String stateCode, String cityCode, boolean completed) {}
  public record SaveProfileRequest(String displayName, String intent, String stateCode, String cityCode,
      Boolean termsAccepted) {}
}
