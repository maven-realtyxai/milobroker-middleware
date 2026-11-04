package com.milobroker.concierge.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Completed-user replies: guardrail first, model second, nudge fallback. No HTTP, no DB. */
@ExtendWith(MockitoExtension.class)
class ConversationRouterTest {
  @Mock OnboardingService onboarding;
  @Mock WhatsAppClient whatsapp;
  @Mock ModelClient model;
  private ConversationRouter router;

  @BeforeEach
  void setup() {
    router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model);
  }

  private ProfileDto completed() {
    return new ProfileDto("911234567890", "Ravi", "RENTAL", "KA", "BLR", true);
  }

  @Test
  void allowedMessageGetsModelAnswer() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(model.isConfigured()).thenReturn(true);
    when(model.chat(any())).thenReturn("Here are 2 options in BLR.");
    router.reply("911234567890", "Ravi", "2bhk rent in Bengaluru", "text");
    verify(model).chat(contains("2bhk rent in Bengaluru"));
    verify(whatsapp).sendText(eq("911234567890"), eq("Here are 2 options in BLR."));
  }

  @Test
  void blockedMessageGetsClarityNudge() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    router.reply("911234567890", "Ravi", "help with my math homework", "text");
    verify(model, never()).chat(any());
    verify(whatsapp).sendText(eq("911234567890"), contains("Ravi"));
  }

  @Test
  void voiceGetsVoiceNudge() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    router.reply("911234567890", "Ravi", null, "audio");
    verify(model, never()).chat(any());
    verify(whatsapp).sendText(eq("911234567890"), contains("voice notes"));
  }

  @Test
  void incompleteProfileStaysSilent() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", null, null, null, null, false));
    router.reply("911234567890", "Ravi", "2bhk rent", "text");
    verify(whatsapp, never()).sendText(any(), any());
    verify(model, never()).chat(any());
  }

  @Test
  void modelFailureFallsBackToNudge() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(model.isConfigured()).thenReturn(true);
    when(model.chat(any())).thenThrow(new RuntimeException("boom"));
    router.reply("911234567890", "Ravi", "2bhk rent in Bengaluru", "text");
    verify(whatsapp).sendText(eq("911234567890"), contains("catching up"));
  }

  @Test
  void unconfiguredModelSendsFallback() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(model.isConfigured()).thenReturn(false);
    router.reply("911234567890", "Ravi", "2bhk rent in Bengaluru", "text");
    verify(model, never()).chat(any());
    verify(whatsapp).sendText(eq("911234567890"), contains("catching up"));
  }
}
