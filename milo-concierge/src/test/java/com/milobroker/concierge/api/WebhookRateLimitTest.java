package com.milobroker.concierge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.service.ConversationRouter;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.RateLimiterService;
import com.milobroker.concierge.service.WhatsAppClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** 25 messages per hour per sender, then one pause notice, then silence. */
@ExtendWith(MockitoExtension.class)
class WebhookRateLimitTest {
  @Mock OnboardingService onboarding;
  @Mock WhatsAppClient whatsapp;
  @Mock ConversationRouter router;

  @BeforeEach
  void setup() {
    lenient().when(onboarding.profile(any())).thenAnswer(inv ->
      new ProfileDto(inv.getArgument(0), "Ravi", "RENTAL", "KA", "BLR", true));
  }

  private WhatsAppWebhookController webhook(RateLimiterService limiter) {
    return new WhatsAppWebhookController("secret-token", onboarding, whatsapp,
      new InputGuardrailService(), router, null, null, null, limiter);
  }

  private String textPayload(String from, String body) {
    return "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"messages\":[{\"from\":\"" + from + "\",\"type\":\"text\","
      + "\"text\":{\"body\":\"" + body + "\"}}]}}]}]}";
  }

  @Test
  void capsAtTwentyFiveThenNotifiesOnce() {
    WhatsAppWebhookController controller = webhook(new RateLimiterService(25, 1));
    for (int i = 0; i < 25; i++) {
      var response = controller.receive(textPayload("9198", "hi " + i), null);
      assertEquals(HttpStatus.OK, response.getStatusCode());
    }
    verify(router, times(25)).reply(eq("9198"), any(), any(), any());
    controller.receive(textPayload("9198", "one more"), null);
    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(whatsapp, times(1)).sendText(eq("9198"), bodies.capture());
    assertTrue(bodies.getValue().contains("pausing replies"));
    controller.receive(textPayload("9198", "and another"), null);
    verify(whatsapp, times(1)).sendText(eq("9198"), any());
    verify(router, times(25)).reply(eq("9198"), any(), any(), any());
  }

  @Test
  void nullLimiterKeepsOldBehavior() {
    WhatsAppWebhookController controller = webhook(null);
    var response = controller.receive(textPayload("9198", "hello"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(router, times(1)).reply(eq("9198"), any(), any(), any());
  }

  @Test
  void otherSendersAreUnaffected() {
    WhatsAppWebhookController controller = webhook(new RateLimiterService(1, 1));
    controller.receive(textPayload("9198", "first"), null);
    controller.receive(textPayload("9198", "second"), null);
    controller.receive(textPayload("9111", "hello"), null);
    verify(router, times(1)).reply(eq("9198"), any(), any(), any());
    verify(router, times(1)).reply(eq("9111"), any(), any(), any());
  }
}
