package com.milobroker.concierge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.dto.OnboardingDtos.AtlasCity;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasState;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import com.milobroker.concierge.service.AtlasGeoClient;
import com.milobroker.concierge.service.ChatOnboardingService;
import com.milobroker.concierge.service.ConversationRouter;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.MarketplaceListingsClient;
import com.milobroker.concierge.service.ModelClient;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.OutputGuardrailService;
import com.milobroker.concierge.service.PostPropertyFlowService;
import com.milobroker.concierge.service.WhatsAppClient;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Full chat journey through the webhook: onboard -> post -> chat. No Flow. */
@ExtendWith(MockitoExtension.class)
class ChatJourneyTest {
  @Mock OnboardingService onboarding;
  @Mock AtlasGeoClient atlas;
  @Mock ConversationSessionRepository sessions;
  @Mock WhatsAppClient whatsapp;
  @Mock MarketplaceListingsClient listings;
  @Mock ModelClient model;
  private final Map<String, ConversationSession> store = new ConcurrentHashMap<>();
  private final Set<String> completed = new HashSet<>();
  private WhatsAppWebhookController webhook;

  @BeforeEach
  void setup() {
    store.clear();
    completed.clear();
    lenient().when(sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc(any(), any()))
      .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(1))));
    lenient().when(sessions.save(any())).thenAnswer(inv -> {
      ConversationSession saved = inv.getArgument(0);
      store.put(saved.getChannelUserId(), saved);
      return saved;
    });
    lenient().when(atlas.states()).thenReturn(List.of(
      new AtlasState("KA", "Karnataka"), new AtlasState("TN", "Tamil Nadu")));
    lenient().when(atlas.cities("KA")).thenReturn(List.of(new AtlasCity("BLR", "Bengaluru", "KA")));
    lenient().when(atlas.cities("TN")).thenReturn(List.of(new AtlasCity("MAA", "Chennai", "TN")));
    when(onboarding.profile(any())).thenAnswer(inv -> {
      String from = inv.getArgument(0);
      if (completed.contains(from)) {
        return new ProfileDto(from, "Asha", "BUY", "KA", "BLR", true);
      }
      return new ProfileDto(from, null, null, null, null, false);
    });
    lenient().when(onboarding.complete(any(), any())).thenAnswer(inv -> {
      String from = inv.getArgument(0);
      CompletionRequest request = inv.getArgument(1);
      completed.add(from);
      return new ProfileDto(from, request.displayName(), request.intent(),
        request.stateCode(), request.cityCode(), true);
    });
    lenient().when(listings.activeCount(any())).thenReturn(0);
    lenient().when(listings.createListing(any(), any(), any(), any(), any(), any(), any(), any(), any()))
      .thenReturn("abcdef-1234-uuid");
    lenient().when(model.isConfigured()).thenReturn(true);
    lenient().when(model.chat(any())).thenReturn(
      "Rentals near Bengaluru usually list quickly, so shortlist two localities and compare deposits.");
    InputGuardrailService guardrail = new InputGuardrailService();
    ChatOnboardingService chat = new ChatOnboardingService(onboarding, atlas, sessions, whatsapp, guardrail);
    PostPropertyFlowService postFlow = new PostPropertyFlowService(sessions, whatsapp, guardrail, listings);
    ConversationRouter router = new ConversationRouter(onboarding, whatsapp, guardrail, model,
      chat, postFlow, new OutputGuardrailService(), null, "http://localhost:8082");
    webhook = new WhatsAppWebhookController("secret-token", onboarding, whatsapp, guardrail, router, chat);
  }

  private String textPayload(String from, String sender, String body) {
    return "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"" + sender + "\"}}],"
      + "\"messages\":[{\"from\":\"" + from + "\",\"type\":\"text\","
      + "\"text\":{\"body\":\"" + body + "\"}}]}}]}]}";
  }

  private void send(String from, String body) {
    var response = webhook.receive(textPayload(from, "Ravi", body), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  private List<String> allTexts(String from) {
    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(whatsapp, org.mockito.Mockito.atLeastOnce()).sendText(eq(from), bodies.capture());
    return bodies.getAllValues();
  }

  @Test
  void onboardPostThenChatEndToEnd() {
    String from = "91999";
    send(from, "Asha");
    send(from, "2");
    send(from, "karnataka");
    send(from, "blr");
    send(from, "YES");
    verify(onboarding).complete(eq(from), any(CompletionRequest.class));
    assertTrue(allTexts(from).get(allTexts(from).size() - 1).contains("Thanks Asha"));

    send(from, "I want to post my flat");
    send(from, "rent");
    send(from, "apartment");
    send(from, "KA, BLR");
    send(from, "2");
    send(from, "25000");
    send(from, "Sunny 2BHK");
    send(from, "Bright flat near metro");
    verify(listings).createListing(eq(from), eq("Sunny 2BHK"), eq("Bright flat near metro"),
      eq("RENT"), eq("APARTMENT"), eq("KA"), eq("BLR"), eq(2), eq(25000L));
    assertTrue(allTexts(from).get(allTexts(from).size() - 1).contains("Posted! ref abcdef"));

    send(from, "2bhk under 30000 in BLR");
    List<String> texts = allTexts(from);
    assertTrue(texts.get(texts.size() - 1).contains("Bengaluru"));

    verify(whatsapp, never()).sendOnboardingFlow(any(), any());
    for (String text : texts) {
      assertTrue(text.trim().split("\\s+").length <= 60, "reply too long: " + text);
      assertTrue(!text.contains("http"), "link leaked: " + text);
    }
  }
}
