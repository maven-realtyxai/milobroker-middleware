package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.json.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Request shape and reply parsing. The thin HTTP call itself is not tested here. */
class MetaModelClientTest {
  private MetaModelClient client;

  @BeforeEach
  void setup() {
    client = new MetaModelClient(RestClient.builder(), "https://api.meta.ai/v1",
      "key", "test-model", "", 0.3, 300);
  }

  @Test
  void unconfiguredWhenKeyOrModelMissing() {
    assertTrue(!new MetaModelClient(RestClient.builder(), "https://x", "",
      "m", "", 0.3, 300).isConfigured());
  }

  @Test
  void requestBodyCarriesModelAndMessages() {
    String body = client.buildRequestBody("sys", "hello");
    assertTrue(body.contains("\"test-model\""));
    assertTrue(body.contains("\"role\":\"system\""));
    assertTrue(body.contains("\"role\":\"user\""));
    assertTrue(body.contains("hello"));
  }

  @Test
  void replyExtractsFirstChoiceContent() {
    String json = Json.createObjectBuilder()
      .add("choices", Json.createArrayBuilder()
        .add(Json.createObjectBuilder()
          .add("message", Json.createObjectBuilder().add("content", "  Hi there  "))))
      .build().toString();
    assertEquals("Hi there", client.parseReply(json));
  }

  @Test
  void emptyChoicesAreRejected() {
    String json = Json.createObjectBuilder()
      .add("choices", Json.createArrayBuilder()).build().toString();
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> client.parseReply(json));
    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void chatWithoutConfigIsUnavailable() {
    MetaModelClient off = new MetaModelClient(RestClient.builder(), "https://x", "",
      "m", "", 0.3, 300);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> off.chat("hi"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }
}
