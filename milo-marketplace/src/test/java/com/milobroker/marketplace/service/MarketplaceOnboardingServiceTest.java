package com.milobroker.marketplace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.marketplace.domain.OnboardingProfile;
import com.milobroker.marketplace.repository.OnboardingProfileRepository;
import com.milobroker.marketplace.service.MarketplaceOnboardingService.SaveProfileRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** User-record persistence owned by Marketplace. */
@ExtendWith(MockitoExtension.class)
class MarketplaceOnboardingServiceTest {
  @Mock OnboardingProfileRepository profiles;
  @InjectMocks MarketplaceOnboardingService service;

  @Test
  void missingRowReadsAsIncomplete() {
    when(profiles.findById("ghost")).thenReturn(Optional.empty());
    var fetched = service.fetch("ghost");
    assertEquals("ghost", fetched.channelUserId());
    assertFalse(fetched.completed());
  }

  @Test
  void saveUpsertsAndCompletes() {
    when(profiles.findById("user-1")).thenReturn(Optional.empty());
    when(profiles.save(any())).thenAnswer(inv -> inv.getArgument(0));
    var saved = service.save("user-1",
      new SaveProfileRequest("Asha", "RENTAL", "KA", "BLR"));
    assertTrue(saved.completed());
    assertEquals("Asha", saved.displayName());
    verify(profiles).save(any(OnboardingProfile.class));
  }

  @Test
  void blankFieldsAreRejected() {
    assertThrows(ResponseStatusException.class, () -> service.save("user-1",
      new SaveProfileRequest(" ", "RENTAL", "KA", "BLR")));
  }
}
