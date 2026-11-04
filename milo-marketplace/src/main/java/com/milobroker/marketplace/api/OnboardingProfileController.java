package com.milobroker.marketplace.api;
import com.milobroker.marketplace.service.MarketplaceOnboardingService;
import com.milobroker.marketplace.service.MarketplaceOnboardingService.ProfileRecord;
import com.milobroker.marketplace.service.MarketplaceOnboardingService.SaveProfileRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
/** User-record boundary for Concierge. Concierge never touches bizdb directly. */
@RestController @RequestMapping("/api/v1/onboarding/profiles")
public class OnboardingProfileController {
  private final MarketplaceOnboardingService onboarding;
  public OnboardingProfileController(MarketplaceOnboardingService onboarding) {
    this.onboarding = onboarding;
  }
  @GetMapping
  public ProfileRecord fetch(@RequestParam String channelUserId) {
    return onboarding.fetch(channelUserId);
  }
  @PostMapping
  public ProfileRecord save(@RequestParam String channelUserId,
      @Valid @RequestBody SaveProfilePayload payload) {
    return onboarding.save(channelUserId, new SaveProfileRequest(
      payload.displayName(), payload.intent(), payload.stateCode(), payload.cityCode(), payload.termsAccepted()));
  }
  @PostMapping("/welcome")
  public boolean claimWelcome(@RequestParam String channelUserId,
      @RequestParam(required = false) String displayName) {
    return onboarding.claimWelcome(channelUserId, displayName);
  }
  @DeleteMapping("/welcome")
  public void releaseWelcome(@RequestParam String channelUserId) {
    onboarding.releaseWelcome(channelUserId);
  }
  public record SaveProfilePayload(@NotBlank String displayName, @NotBlank String intent,
      @NotBlank String stateCode, @NotBlank String cityCode, Boolean termsAccepted) {}
}
