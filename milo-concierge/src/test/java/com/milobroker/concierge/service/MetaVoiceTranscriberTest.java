package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Transcript parsing and failure mapping, through a mocked STT endpoint. */
class MetaVoiceTranscriberTest {
  private RestClient.Builder builder;
  private MockRestServiceServer server;

  @BeforeEach
  void setup() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
  }

  private MetaVoiceTranscriber transcriber() {
    return new MetaVoiceTranscriber(builder, "https://api.meta.ai/v1/asr/transcribe",
      "muse-voice-transcribe-1.0", "key", true, "Kannada,Hindi,English", "BHK,rent");
  }

  /** Matches the doc-shaped multipart body without decoding the WAV bytes. */
  private static RequestMatcher multipartWith(String snippet) {
    return request -> {
      byte[] raw = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
        .getBodyAsBytes();
      String body = new String(raw, StandardCharsets.ISO_8859_1);
      if (!body.contains(snippet)) {
        throw new AssertionError("multipart body missing: " + snippet);
      }
    };
  }

  @Test
  void transcribeReturnsTrimmedTranscript() {
    server.expect(requestTo("https://api.meta.ai/v1/asr/transcribe?sessionId=s1"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(multipartWith("\"audioEncoding\":\"WAV\""))
      .andRespond(withSuccess("{\"transcript\":\"  2bhk in Whitefield \"}",
        MediaType.APPLICATION_JSON));
    assertEquals("2bhk in Whitefield",
      transcriber().transcribe(new byte[] {1, 2}, "s1"));
    server.verify();
  }

  @Test
  void serverErrorBecomesBadGateway() {
    server.expect(requestTo("https://api.meta.ai/v1/asr/transcribe?sessionId=s1"))
      .andRespond(withServerError());
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> transcriber().transcribe(new byte[] {1}, "s1"));
    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void emptyTranscriptIsRejected() {
    server.expect(requestTo("https://api.meta.ai/v1/asr/transcribe?sessionId=s1"))
      .andRespond(withSuccess("{\"transcript\":\"   \"}", MediaType.APPLICATION_JSON));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> transcriber().transcribe(new byte[] {1}, "s1"));
    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void requestJsonCarriesModeModelAndBias() {
    String json = transcriber().buildRequestJson();
    assertTrue(json.contains("\"mode\":\"PUSH_TO_TALK\""));
    assertTrue(json.contains("\"model\":\"muse-voice-transcribe-1.0\""));
    assertTrue(json.contains("\"languageBias\":[\"Kannada\",\"Hindi\",\"English\"]"));
    assertTrue(json.contains("\"keywords\":[\"BHK\",\"rent\"]"));
  }

  @Test
  void unconfiguredWhenKeyMissing() {
    assertTrue(!new MetaVoiceTranscriber(RestClient.builder(),
      "https://api.meta.ai/v1/asr/transcribe", "muse-voice-transcribe-1.0", "", true,
      "English", "").isConfigured());
  }

  @Test
  void disabledFlagMeansUnconfigured() {
    assertTrue(!new MetaVoiceTranscriber(RestClient.builder(),
      "https://api.meta.ai/v1/asr/transcribe", "muse-voice-transcribe-1.0", "key", false,
      "English", "").isConfigured());
  }
}
