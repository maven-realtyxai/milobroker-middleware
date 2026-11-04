package com.milobroker.concierge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import com.milobroker.concierge.service.AtlasGeoClient;
import com.milobroker.concierge.service.ChatOnboardingService;
import com.milobroker.concierge.service.ConversationRouter;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.MetaVoiceTranscriber;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.VoiceNoteService;
import com.milobroker.concierge.service.WhatsAppClient;
import com.milobroker.concierge.service.WhatsAppMediaClient;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Voice notes become chat text before the guardrail; failures keep the static reply. */
@ExtendWith(MockitoExtension.class)
class WebhookVoiceTest {
  @Mock OnboardingService onboarding;
  @Mock AtlasGeoClient atlas;
  @Mock ConversationSessionRepository sessions;
  @Mock WhatsAppClient whatsapp;
  @Mock ConversationRouter router;
  private final Map<String, ConversationSession> store = new ConcurrentHashMap<>();
  private RestClient.Builder mediaBuilder;
  private MockRestServiceServer mediaServer;
  private RestClient.Builder transcribeBuilder;
  private MockRestServiceServer transcribeServer;

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
    lenient().when(onboarding.profile(any())).thenAnswer(inv ->
      new ProfileDto(inv.getArgument(0), null, null, null, null, false));
    mediaBuilder = RestClient.builder();
    mediaServer = MockRestServiceServer.bindTo(mediaBuilder).build();
    transcribeBuilder = RestClient.builder();
    transcribeServer = MockRestServiceServer.bindTo(transcribeBuilder).build();
  }

  private WhatsAppWebhookController webhook() {
    VoiceNoteService voice = new VoiceNoteService(
      new WhatsAppMediaClient(mediaBuilder, "https://graph.facebook.com/v21.0", "tok", 100),
      source -> source,
      new MetaVoiceTranscriber(transcribeBuilder, "https://api.meta.ai/v1/asr/transcribe",
        "muse-voice-transcribe-1.0", "key", true, "English", ""));
    ChatOnboardingService chat = new ChatOnboardingService(onboarding, atlas, sessions,
      whatsapp, new InputGuardrailService());
    return new WhatsAppWebhookController("secret-token", onboarding, whatsapp,
      new InputGuardrailService(), router, null, chat, voice);
  }

  private String audioPayload(String from, String sender) {
    return "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"" + sender + "\"}}],"
      + "\"messages\":[{\"from\":\"" + from + "\",\"type\":\"audio\","
      + "\"audio\":{\"id\":\"a1\"}}]}}]}]}";
  }

  private void stubMediaOk() {
    mediaServer.expect(requestTo("https://graph.facebook.com/v21.0/a1"))
      .andRespond(withSuccess(
        "{\"url\":\"https://graph.facebook.com/v21.0/f1\",\"mime_type\":\"audio/ogg\"}",
        MediaType.APPLICATION_JSON));
    mediaServer.expect(requestTo("https://graph.facebook.com/v21.0/f1"))
      .andRespond(withSuccess("OGGDATA", MediaType.APPLICATION_OCTET_STREAM));
  }

  private String lastText(String from) {
    ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
    verify(whatsapp, org.mockito.Mockito.atLeastOnce()).sendText(eq(from), bodies.capture());
    List<String> all = bodies.getAllValues();
    return all.get(all.size() - 1);
  }

  @Test
  void voiceTranscriptJoinsOnboardingAsName() {
    stubMediaOk();
    transcribeServer.expect(requestTo(
        org.hamcrest.Matchers.startsWith("https://api.meta.ai/v1/asr/transcribe?sessionId=")))
      .andRespond(withSuccess("{\"transcript\":\"Asha Rao\"}", MediaType.APPLICATION_JSON));
    var response = webhook().receive(audioPayload("9198", "Ravi"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(lastText("9198").contains("1) Rent"));
    assertEquals(ChatOnboardingService.STATE_ASK_ROLE, store.get("9198").getState());
    mediaServer.verify();
    transcribeServer.verify();
  }

  @Test
  void transcriptionFailureKeepsStaticReply() {
    stubMediaOk();
    transcribeServer.expect(requestTo(
        org.hamcrest.Matchers.startsWith("https://api.meta.ai/v1/asr/transcribe?sessionId=")))
      .andRespond(withServerError());
    var response = webhook().receive(audioPayload("9199", "Ravi"), null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(lastText("9199").contains("can't listen"));
    assertEquals(ChatOnboardingService.STATE_ASK_NAME, store.get("9199").getState());
  }
}
