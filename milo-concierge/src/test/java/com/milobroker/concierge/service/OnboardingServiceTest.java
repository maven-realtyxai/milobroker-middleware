package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.dto.OnboardingDtos.AtlasCity;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasState;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** Validation stays in Concierge; persistence goes through the Marketplace client. */
@ExtendWith(MockitoExtension.class)
class OnboardingServiceTest {
  @Mock AtlasGeoClient atlas;
  @Mock MarketplaceProfilesClient profiles;
  private OnboardingService service;

  @BeforeEach
  void setup() {
    service = new OnboardingService(profiles, atlas);
  }

  private void givenStates() {
    when(atlas.states()).thenReturn(List.of(
      new AtlasState("KA", "Karnataka"), new AtlasState("TN", "Tamil Nadu")));
  }

  @Test
  void happyPathValidatesThenSaves() {
    givenStates();
    when(atlas.cities("KA")).thenReturn(List.of(
      new AtlasCity("BLR", "Bengaluru", "KA")));
    when(profiles.save(eq("user-1"), eq("Asha"), eq("RENTAL"), eq("KA"), eq("BLR"), eq(true))).thenReturn(
      new ProfileDto("user-1", "Asha", "RENTAL", "KA", "BLR", true));
    var saved = service.complete("user-1",
      new CompletionRequest("Asha", "Find a rental", "ka", "blr", true));
    assertTrue(saved.completed());
    assertEquals("RENTAL", saved.intent());
    verify(profiles).save("user-1", "Asha", "RENTAL", "KA", "BLR", true);
  }

  @Test
  void cityFromAnotherStateNeverReachesMarketplace() {
    givenStates();
    when(atlas.cities("TN")).thenReturn(List.of(
      new AtlasCity("MAA", "Chennai", "TN")));
    assertThrows(ResponseStatusException.class, () -> service.complete("user-1",
      new CompletionRequest("Asha", "Rent", "TN", "BLR", true)));
    verify(profiles, org.mockito.Mockito.never())
      .save(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void unknownIntentIsRejected() {
    assertThrows(ResponseStatusException.class, () -> service.complete("user-1",
      new CompletionRequest("Asha", "Fly to the moon", "KA", "BLR", true)));
  }

  @Test
  void termsConsentIsRequiredBeforeSaving() {
    assertThrows(ResponseStatusException.class, () -> service.complete("user-1",
      new CompletionRequest("Asha", "Rent", "KA", "BLR", false)));
    verify(profiles, org.mockito.Mockito.never())
      .save(anyString(), anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void stateOptionsComeFromAtlas() {
    givenStates();
    assertEquals(2, service.states().size());
    assertEquals("KA", service.states().get(0).id());
  }
}
