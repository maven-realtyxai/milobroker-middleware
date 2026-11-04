package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Media lookup plus download, through a mocked Cloud API. No sockets. */
class WhatsAppMediaClientTest {
  private RestClient.Builder builder;
  private MockRestServiceServer server;

  @BeforeEach
  void setup() {
    builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
  }

  @Test
  void downloadsAudioBytesWithMimeType() {
    server.expect(requestTo("https://graph.facebook.com/v21.0/a1"))
      .andExpect(header("Authorization", "Bearer tok"))
      .andRespond(withSuccess(
        "{\"url\":\"https://graph.facebook.com/v21.0/f1\",\"mime_type\":\"audio/ogg\"}",
        MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://graph.facebook.com/v21.0/f1"))
      .andRespond(withSuccess("OGGDATA", MediaType.APPLICATION_OCTET_STREAM));
    WhatsAppMediaClient client = new WhatsAppMediaClient(builder,
      "https://graph.facebook.com/v21.0", "tok", 100);
    WhatsAppMediaClient.AudioBytes out = client.downloadAudio("a1");
    assertEquals("audio/ogg", out.mimeType());
    assertEquals("OGGDATA", new String(out.data(), StandardCharsets.UTF_8));
    server.verify();
  }

  @Test
  void oversizedAudioIsRejected() {
    server.expect(requestTo("https://graph.facebook.com/v21.0/a1"))
      .andRespond(withSuccess(
        "{\"url\":\"https://graph.facebook.com/v21.0/f1\",\"mime_type\":\"audio/ogg\"}",
        MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://graph.facebook.com/v21.0/f1"))
      .andRespond(withSuccess("TOOBIG", MediaType.APPLICATION_OCTET_STREAM));
    WhatsAppMediaClient client = new WhatsAppMediaClient(builder,
      "https://graph.facebook.com/v21.0", "tok", 3);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> client.downloadAudio("a1"));
    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatusCode());
  }

  @Test
  void missingDownloadUrlIsBadGateway() {
    server.expect(requestTo("https://graph.facebook.com/v21.0/a1"))
      .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    WhatsAppMediaClient client = new WhatsAppMediaClient(builder,
      "https://graph.facebook.com/v21.0", "tok", 100);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> client.downloadAudio("a1"));
    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void unconfiguredClientIsUnavailable() {
    WhatsAppMediaClient client = new WhatsAppMediaClient(RestClient.builder(),
      "https://graph.facebook.com/v21.0", "", 100);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> client.downloadAudio("a1"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }
}
