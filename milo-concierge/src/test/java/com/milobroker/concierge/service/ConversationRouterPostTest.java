package com.milobroker.concierge.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

/** Router post/search/plain-chat branches for completed users. No cards. */
@ExtendWith(MockitoExtension.class)
class ConversationRouterPostTest {
  @Mock OnboardingService onboarding;
  @Mock WhatsAppClient whatsapp;
  @Mock ModelClient model;
  @Mock ChatOnboardingService chat;
  @Mock PostPropertyFlowService postFlow;
  private ConversationRouter router;
  private HttpServer stub;

  private ProfileDto completed() {
    return new ProfileDto("911234567890", "Ravi", "RENTAL", "KA", "BLR", true);
  }

  @BeforeEach
  void setup() {
    router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model,
      chat, postFlow, new OutputGuardrailService(), RestClient.builder(),
      "http://127.0.0.1:1");
  }

  @AfterEach
  void tearDown() {
    if (stub != null) {
      stub.stop(0);
      stub = null;
    }
  }

  @Test
  void postKeywordRoutesToPostFlow() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(postFlow.isInProgress("911234567890")).thenReturn(false);
    router.reply("911234567890", "Ravi", "I want to list my property", "text");
    verify(postFlow).handle(eq("911234567890"), eq("Ravi"),
      eq("I want to list my property"), eq("text"));
    verify(model, never()).chat(any());
    verify(whatsapp, never()).sendText(any(), any());
  }

  @Test
  void inProgressDraftRoutesToPostFlow() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(postFlow.isInProgress("911234567890")).thenReturn(true);
    router.reply("911234567890", "Ravi", "hello there", "text");
    verify(postFlow).handle(eq("911234567890"), eq("Ravi"), eq("hello there"), eq("text"));
    verify(model, never()).chat(any());
  }

  @Test
  void plainChatGoesThroughModelAndOutputGuardrail() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(postFlow.isInProgress("911234567890")).thenReturn(false);
    when(model.isConfigured()).thenReturn(true);
    when(model.chat(any())).thenReturn("Here are 2 options in BLR.");
    router.reply("911234567890", "Ravi", "Need help with agreement paperwork", "text");
    verify(model).chat(contains("Need help with agreement paperwork"));
    verify(whatsapp).sendText(eq("911234567890"), eq("Here are 2 options in BLR."));
  }

  @Test
  void modelPiiFallsBack() {
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(postFlow.isInProgress("911234567890")).thenReturn(false);
    when(model.isConfigured()).thenReturn(true);
    when(model.chat(any())).thenReturn("Call 98765 43210 now");
    router.reply("911234567890", "Ravi", "Need help with agreement paperwork", "text");
    verify(whatsapp).sendText(eq("911234567890"), contains("catching up"));
  }

  @Test
  void searchResultsSendNumberedList() throws IOException {
    String payload = "{\"appliedFilters\":{},\"results\":[{"
      + "\"listingId\":\"abc123\",\"title\":\"Sunny 2BHK\",\"priceInr\":25000,"
      + "\"localityCode\":\"WHITEFIELD\",\"status\":\"ACTIVE\",\"bedrooms\":2}],\"total\":1}";
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/api/v1/search", exchange -> {
      byte[] out = payload.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, out.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(out);
      }
    });
    stub.start();
    router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model,
      chat, postFlow, new OutputGuardrailService(), RestClient.builder(),
      "http://127.0.0.1:" + stub.getAddress().getPort());
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(postFlow.isInProgress("911234567890")).thenReturn(false);
    router.reply("911234567890", "Ravi", "2bhk rent in Whitefield", "text");
    verify(whatsapp).sendText(eq("911234567890"), contains("1. Sunny 2BHK"));
    verify(model, never()).chat(any());
  }

  @Test
  void emptySearchSendsGracefulNotice() throws IOException {
    String payload = "{\"appliedFilters\":{},\"results\":[],\"total\":0}";
    stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    stub.createContext("/api/v1/search", exchange -> {
      byte[] out = payload.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, out.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(out);
      }
    });
    stub.start();
    router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model,
      chat, postFlow, new OutputGuardrailService(), RestClient.builder(),
      "http://127.0.0.1:" + stub.getAddress().getPort());
    when(onboarding.profile("911234567890")).thenReturn(completed());
    when(postFlow.isInProgress("911234567890")).thenReturn(false);
    router.reply("911234567890", "Ravi", "2bhk rent in Whitefield", "text");
    verify(whatsapp).sendText(eq("911234567890"), contains("no properties listed in Whitefield"));
    verify(model, never()).chat(any());
  }

  @Test
  void incompleteUserDelegatesToOnboardingChat() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", null, null, null, null, false));
    router.reply("911234567890", "Ravi", "My name is Asha", "text");
    verify(chat).handle(eq("911234567890"), eq("Ravi"), eq("My name is Asha"), eq("text"));
    verify(model, never()).chat(any());
    verify(whatsapp, never()).sendText(any(), any());
  }
}
