package com.milobroker.concierge.service;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
/** User-record persistence behind the Marketplace API. */
public interface MarketplaceProfilesClient {
  ProfileDto fetch(String channelUserId);
  boolean claimWelcome(String channelUserId, String displayName);
  void releaseWelcome(String channelUserId);
  ProfileDto save(String channelUserId, String displayName, String intent, String stateCode, String cityCode,
      boolean termsAccepted);
}
