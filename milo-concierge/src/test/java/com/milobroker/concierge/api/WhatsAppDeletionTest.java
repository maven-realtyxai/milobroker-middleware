package com.milobroker.concierge.api;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.milobroker.concierge.service.*;
import org.junit.jupiter.api.Test;

class WhatsAppDeletionTest {
  private final OnboardingService onboarding = mock(OnboardingService.class);
  private final WhatsAppClient whatsapp = mock(WhatsAppClient.class);
  private final UserDeletionService deletion = mock(UserDeletionService.class);
  private final WhatsAppWebhookController webhook = new WhatsAppWebhookController("token", onboarding,
    whatsapp, new InputGuardrailService(), mock(ConversationRouter.class), deletion);
  private String payload(String body) {
    return "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"from\":\"91123\",\"type\":\"text\",\"text\":{\"body\":\"" + body + "\"}}]}}]}]}";
  }
  @Test void deletionRequiresVerifiedWebhooks() {
    webhook.receive(payload("DELETE MY DATA"), null);
    verify(deletion, never()).request(any());
    verify(whatsapp).sendText(eq("91123"), contains("not available"));
  }
  @Test void requestsConfirmationBeforeDeleting() {
    when(whatsapp.verifiesWebhookSignatures()).thenReturn(true);
    when(deletion.request("91123")).thenReturn("123ABC");
    webhook.receive(payload("delete my data"), "signature");
    verify(whatsapp).sendText(eq("91123"), contains("CONFIRM DELETE 123ABC"));
    verify(deletion, never()).confirm(any(), any());
  }
  @Test void confirmationUsesAuthenticatedSenderAndCode() {
    when(whatsapp.verifiesWebhookSignatures()).thenReturn(true);
    when(deletion.confirm("91123", "123ABC")).thenReturn(true);
    webhook.receive(payload("CONFIRM DELETE 123ABC"), "signature");
    verify(deletion).confirm("91123", "123ABC");
    verify(whatsapp).sendText(eq("91123"), contains("deleted"));
  }
  @Test void failureDoesNotClaimSuccess() {
    when(whatsapp.verifiesWebhookSignatures()).thenReturn(true);
    when(deletion.confirm(any(), any())).thenThrow(new IllegalStateException());
    webhook.receive(payload("CONFIRM DELETE 123ABC"), "signature");
    verify(whatsapp).sendText(eq("91123"), contains("could not complete"));
  }
}
