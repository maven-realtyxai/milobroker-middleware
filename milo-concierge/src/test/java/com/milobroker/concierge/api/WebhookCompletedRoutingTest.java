package com.milobroker.concierge.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.service.ConversationRouter;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.WhatsAppClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Completed users route to the model router; incomplete users stay on welcome. */
@ExtendWith(MockitoExtension.class)
class WebhookCompletedRoutingTest {
  @Mock OnboardingService onboarding;
  @Mock WhatsAppClient whatsapp;
  @Mock ConversationRouter router;

  private String textPayload(String from, String body) {
    return "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"Ravi\"}}],"
      + "\"messages\":[{\"from\":\"" + from + "\",\"type\":\"text\","
      + "\"text\":{\"body\":\"" + body + "\"}}]}}]}]}";
  }

  private WhatsAppWebhookController webhook() {
    return new WhatsAppWebhookController("secret-token", onboarding, whatsapp,
      new InputGuardrailService(), router);
  }

  @Test
  void completedUserRoutesToModel() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", "Ravi", "RENTAL", "KA", "BLR", true));
    var response = webhook().receive(textPayload("911234567890", "2bhk rent?"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(router).reply(eq("911234567890"), eq("Ravi"), eq("2bhk rent?"), eq("text"));
    verify(whatsapp, never()).sendText(any(), any());
  }

  @Test
  void imageAndVideoIdsReachThePostingFlow() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", "Ravi", "RENTAL", "KA", "BLR", true));
    var payload = """
      {"entry":[{"changes":[{"value":{"messages":[
        {"from":"911234567890","type":"image","image":{"id":"photo1"}},
        {"from":"911234567890","type":"video","video":{"id":"video1"}}
      ]}}]}]}
      """;
    assertEquals(HttpStatus.OK, webhook().receive(payload, null).getStatusCode());
    verify(router).receiveMedia("911234567890", "photo1", "image");
    verify(router).receiveMedia("911234567890", "video1", "video");
    verify(router, never()).reply(any(), any(), any(), any());
  }

  @Test
  void incompleteUserStaysOnWelcomePath() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", null, null, null, null, false));
    var response = webhook().receive(textPayload("911234567890", "hi"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(router, never()).reply(any(), any(), any(), any());
  }
}
