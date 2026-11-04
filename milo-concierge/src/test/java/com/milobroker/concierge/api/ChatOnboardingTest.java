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
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.WhatsAppClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/** Plain-text onboarding slot machine through the webhook. No Flow, no database. */
@ExtendWith(MockitoExtension.class)
class ChatOnboardingTest {
  @Mock OnboardingService onboarding;
  @Mock AtlasGeoClient atlas;
  @Mock ConversationSessionRepository sessions;
  @Mock WhatsAppClient whatsapp;
  @Mock ConversationRouter router;
  private final Map<String, ConversationSession> store = new ConcurrentHashMap<>();
  private WhatsAppWebhookController webhook;
  private ChatOnboardingService chat;

  @BeforeEach
  void setup() {
    store.clear();
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
    when(onboarding.profile(any())).thenAnswer(inv ->
      new ProfileDto(inv.getArgument(0), null, null, null, null, false));
    lenient().when(onboarding.complete(any(), any())).thenAnswer(inv -> {
      CompletionRequest request = inv.getArgument(1);
      return new ProfileDto(inv.getArgument(0), request.displayName(), request.intent(),
        request.stateCode(), request.cityCode(), true);
    });
    chat = new ChatOnboardingService(onboarding, atlas, sessions, whatsapp,
      new InputGuardrailService());
    webhook = new WhatsAppWebhookController("secret-token", onboarding, whatsapp,
      new InputGuardrailService(), router, chat);
  }

  private String textPayload(String from, String sender, String body) {
    return "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"" + sender + "\"}}],"
      + "\"messages\":[{\"from\":\"" + from + "\",\"type\":\"text\","
      + "\"text\":{\"body\":\"" + body + "\"}}]}}]}]}";
  }

  private String audioPayload(String from, String sender) {
    return "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"" + sender + "\"}}],"
      + "\"messages\":[{\"from\":\"" + from + "\",\"type\":\"audio\","
      + "\"audio\":{\"id\":\"a1\"}}]}}]}]}";
  }

  private void send(String from, String sender, String body) {
    var response = webhook.receive(textPayload(from, sender, body), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  private String lastText(String from) {
    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(whatsapp, org.mockito.Mockito.atLeastOnce()).sendText(eq(from), bodies.capture());
    List<String> all = bodies.getAllValues();
    return all.get(all.size() - 1);
  }

  private void driveToTerms(String from, String sender) {
    send(from, sender, "Asha");
    send(from, sender, "rent");
    send(from, sender, "KA");
    send(from, sender, "BLR");
    assertEquals(ChatOnboardingService.STATE_ASK_TERMS, store.get(from).getState());
  }

  @Test
  void happyPathCompletesWithNormalizedCodes() {
    send("91001", "Ravi", "Asha");
    assertTrue(lastText("91001").contains("1) Rent"));
    send("91001", "Ravi", "2");
    assertTrue(lastText("91001").contains("Which state"));
    send("91001", "Ravi", "ka");
    assertTrue(lastText("91001").contains("Which city"));
    send("91001", "Ravi", "blr");
    assertTrue(lastText("91001").contains("YES"));
    send("91001", "Ravi", "YES");
    ArgumentCaptor<CompletionRequest> completion = ArgumentCaptor.forClass(CompletionRequest.class);
    verify(onboarding).complete(eq("91001"), completion.capture());
    assertEquals("Asha", completion.getValue().displayName());
    assertEquals("BUY", completion.getValue().intent());
    assertEquals("KA", completion.getValue().stateCode());
    assertEquals("BLR", completion.getValue().cityCode());
    assertEquals(Boolean.TRUE, completion.getValue().termsAccepted());
    assertEquals(ChatOnboardingService.STATE_DONE, store.get("91001").getState());
    assertTrue(lastText("91001").contains("Thanks Asha"));
    assertTrue(lastText("91001").contains("complete"));
  }

  @Test
  void roleSynonymsMapToCanonicalRoles() {
    Map<String, String> synonyms = Map.ofEntries(
      Map.entry("rent", "RENTAL"), Map.entry("rental", "RENTAL"),
      Map.entry("buy", "BUY"), Map.entry("purchase", "BUY"), Map.entry("sale", "BUY"),
      Map.entry("list", "LIST"), Map.entry("post", "LIST"), Map.entry("sell", "LIST"),
      Map.entry("agent", "AGENT"), Map.entry("broker", "AGENT"),
      Map.entry("1", "RENTAL"), Map.entry("2", "BUY"),
      Map.entry("3", "LIST"), Map.entry("4", "AGENT"));
    int i = 0;
    for (Map.Entry<String, String> synonym : synonyms.entrySet()) {
      String from = "92" + String.format("%03d", i++);
      send(from, "Ravi", "Asha");
      send(from, "Ravi", synonym.getKey());
      assertEquals(ChatOnboardingService.STATE_ASK_STATE, store.get(from).getState(),
        "synonym " + synonym.getKey());
      assertTrue(store.get(from).getContext().contains("\"role\":\"" + synonym.getValue() + "\""),
        "synonym " + synonym.getKey());
    }
    verify(onboarding, never()).complete(any(), any());
  }

  @Test
  void unknownStateRepromptsWithoutAdvancing() {
    send("91003", "Ravi", "Asha");
    send("91003", "Ravi", "rent");
    send("91003", "Ravi", "XX");
    assertEquals(ChatOnboardingService.STATE_ASK_STATE, store.get("91003").getState());
    assertTrue(lastText("91003").contains("couldn't find"));
    verify(onboarding, never()).complete(any(), any());
    send("91003", "Ravi", "karnataka");
    assertEquals(ChatOnboardingService.STATE_ASK_CITY, store.get("91003").getState());
    assertTrue(store.get("91003").getContext().contains("\"stateCode\":\"KA\""));
  }

  @Test
  void cityFromAnotherStateIsRejected() {
    send("91004", "Ravi", "Asha");
    send("91004", "Ravi", "rent");
    send("91004", "Ravi", "TN");
    send("91004", "Ravi", "BLR");
    assertEquals(ChatOnboardingService.STATE_ASK_CITY, store.get("91004").getState());
    assertTrue(lastText("91004").contains("doesn't look like a city"));
    verify(onboarding, never()).complete(any(), any());
    send("91004", "Ravi", "Chennai");
    assertEquals(ChatOnboardingService.STATE_ASK_TERMS, store.get("91004").getState());
    assertTrue(store.get("91004").getContext().contains("\"cityCode\":\"MAA\""));
  }

  @Test
  void termsNoBlocksCompletion() {
    driveToTerms("91005", "Ravi");
    send("91005", "Ravi", "NO");
    verify(onboarding, never()).complete(any(), any());
    assertEquals(ChatOnboardingService.STATE_ASK_TERMS, store.get("91005").getState());
    assertTrue(lastText("91005").contains("YES"));
    send("91005", "Ravi", "y");
    verify(onboarding).complete(eq("91005"), any(CompletionRequest.class));
    assertTrue(lastText("91005").contains("Thanks Asha"));
  }

  @Test
  void restartAndBackStepThroughStates() {
    send("91006", "Ravi", "Asha");
    send("91006", "Ravi", "rent");
    send("91006", "Ravi", "KA");
    assertEquals(ChatOnboardingService.STATE_ASK_CITY, store.get("91006").getState());
    send("91006", "Ravi", "BACK");
    assertEquals(ChatOnboardingService.STATE_ASK_STATE, store.get("91006").getState());
    assertTrue(lastText("91006").contains("Which state"));
    verify(onboarding, never()).complete(any(), any());
    send("91006", "Ravi", "RESTART");
    assertEquals(ChatOnboardingService.STATE_ASK_NAME, store.get("91006").getState());
    assertTrue(lastText("91006").contains("full name"));
    assertEquals("{}", store.get("91006").getContext());
  }

  @Test
  void voiceNeverAdvancesTheMachine() {
    send("91007", "Ravi", "Asha");
    assertEquals(ChatOnboardingService.STATE_ASK_ROLE, store.get("91007").getState());
    var response = webhook.receive(audioPayload("91007", "Ravi"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(ChatOnboardingService.STATE_ASK_ROLE, store.get("91007").getState());
    assertTrue(lastText("91007").contains("voice"));
    verify(onboarding, never()).complete(any(), any());
  }

  @Test
  void offTopicGetsClarityPlusRepeat() {
    send("91008", "Ravi", "Asha");
    send("91008", "Ravi", "crypto trading tips");
    assertEquals(ChatOnboardingService.STATE_ASK_ROLE, store.get("91008").getState());
    assertTrue(lastText("91008").contains("resend with clarity"));
    assertTrue(lastText("91008").contains("1) Rent"));
    verify(onboarding, never()).complete(any(), any());
  }

  @Test
  void blankInputRepeatsTheQuestion() {
    send("91009", "Ravi", "Asha");
    send("91009", "Ravi", "   ");
    assertEquals(ChatOnboardingService.STATE_ASK_ROLE, store.get("91009").getState());
    assertTrue(lastText("91009").contains("1) Rent"));
  }

  @Test
  void greetingUsesSenderName() {
    send("91010", "Ravi", "hi");
    assertEquals(ChatOnboardingService.STATE_ASK_NAME, store.get("91010").getState());
    assertTrue(lastText("91010").contains("Ravi"));
    assertTrue(lastText("91010").contains("full name"));
  }

  @Test
  void flowIsNeverUsedForOnboarding() {
    lenient().when(whatsapp.isOnboardingFlowConfigured()).thenReturn(true);
    send("91011", "Ravi", "hi");
    verify(whatsapp, never()).sendOnboardingFlow(any(), any());
    verify(onboarding, never()).claimWelcome(any(), any());
    verify(onboarding, never()).releaseWelcome(any());
    verify(whatsapp).sendText(eq("91011"), contains("Ravi"));
  }

  @Test
  void completedUserStillRoutesToModel() {
    ChatOnboardingService chatSpy = org.mockito.Mockito.mock(ChatOnboardingService.class);
    WhatsAppWebhookController routed = new WhatsAppWebhookController("secret-token", onboarding,
      whatsapp, new InputGuardrailService(), router, chatSpy);
    when(onboarding.profile("91012")).thenReturn(
      new ProfileDto("91012", "Ravi", "RENTAL", "KA", "BLR", true));
    var response = routed.receive(textPayload("91012", "Ravi", "2bhk rent?"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(router).reply(eq("91012"), eq("Ravi"), eq("2bhk rent?"), eq("text"));
    verify(chatSpy, never()).handle(any(), any(), any(), any());
    verify(whatsapp, never()).sendText(any(), any());
  }
}
