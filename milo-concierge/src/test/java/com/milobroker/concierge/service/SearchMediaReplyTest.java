package com.milobroker.concierge.service;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SearchMediaReplyTest {
  private MockRestServiceServer server;
  private ConversationRouter router;
  private WhatsAppClient whatsapp;
  private ModelClient model;

  @BeforeEach void setup() {
    var builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    whatsapp = mock(WhatsAppClient.class);
    model = mock(ModelClient.class);
    var onboarding = mock(OnboardingService.class);
    when(onboarding.profile("u1")).thenReturn(new ProfileDto("u1", "Ravi", "RENTAL", "KA", "BLR", true));
    router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model,
      null, null, null, builder, "http://marketplace");
  }

  private JsonObject file(String type, String status, String mime, long bytes, String url) {
    return Json.createObjectBuilder().add("mediaType", type).add("uploadStatus", status)
      .add("contentType", mime).add("sizeBytes", bytes).add("url", url).build();
  }

  private void results(int count) {
    var rows = Json.createArrayBuilder();
    for (int i = 1; i <= count; i++) rows.add(Json.createObjectBuilder()
      .add("listingId", "listing" + i).add("title", "Home " + i)
      .add("priceInr", 25000).add("localityCode", "WHITEFIELD"));
    server.expect(requestTo("http://marketplace/api/v1/search"))
      .andRespond(withSuccess(Json.createObjectBuilder().add("results", rows).build().toString(), MediaType.APPLICATION_JSON));
  }

  private void media(String id, JsonObject... files) {
    var rows = Json.createArrayBuilder();
    for (var file : files) rows.add(file);
    server.expect(requestTo("http://marketplace/api/v1/listings/" + id + "/media"))
      .andRespond(withSuccess(rows.build().toString(), MediaType.APPLICATION_JSON));
  }

  private void search() {
    router.reply("u1", "Ravi", "Search 2 bhk rent in Whitefield", "text");
    verify(whatsapp).sendText(eq("u1"), contains("1. Home 1 - Rs.25000 (WHITEFIELD)"));
    verify(model, never()).chat(any());
    server.verify();
  }

  @Test void prefersOneReadyImageOverVideoAndPendingImages() {
    results(1);
    media("listing1",
      file("IMAGE", "PENDING", "image/jpeg", 100, "https://s3/pending"),
      file("VIDEO", "READY", "video/mp4", 100, "https://s3/video"),
      file("IMAGE", "READY", "image/jpeg", 100, "https://s3/cover?signature=abc"),
      file("IMAGE", "READY", "image/png", 100, "https://s3/second"));
    search();
    verify(whatsapp).sendMedia("u1", "image", "https://s3/cover?signature=abc",
      "1. Home 1 - Rs.25000 (WHITEFIELD)\nRef: listing1");
    verify(whatsapp, times(1)).sendMedia(any(), any(), any(), any());
  }

  @Test void videoOnlyListingSendsVideo() {
    results(1);
    media("listing1", file("VIDEO", "READY", "video/mp4", 100, "https://s3/video"));
    search();
    verify(whatsapp).sendMedia(eq("u1"), eq("video"), eq("https://s3/video"), contains("Ref: listing1"));
  }

  @Test void missingUnsupportedOrOversizedMediaKeepsText() {
    results(2);
    media("listing1");
    media("listing2",
      file("IMAGE", "READY", "image/jpeg", 6 * 1024 * 1024, "https://s3/large"),
      file("IMAGE", "READY", "image/svg+xml", 100, "https://s3/svg"),
      file("VIDEO", "READY", "video/mp4", 17 * 1024 * 1024, "https://s3/large-video"),
      file("IMAGE", "REJECTED", "image/png", 100, "https://s3/rejected"));
    search();
    verify(whatsapp, never()).sendMedia(any(), any(), any(), any());
  }

  @Test void failedLookupAndSendDoNotPreventOtherListingMedia() {
    results(3);
    server.expect(requestTo("http://marketplace/api/v1/listings/listing1/media")).andRespond(withServerError());
    media("listing2", file("IMAGE", "READY", "image/jpeg", 100, "https://s3/broken"));
    media("listing3", file("IMAGE", "READY", "image/png", 100, "https://s3/working"));
    when(whatsapp.sendMedia(eq("u1"), eq("image"), eq("https://s3/broken"), any()))
      .thenThrow(new IllegalStateException("Rejected by Meta"));
    search();
    verify(whatsapp).sendMedia(eq("u1"), eq("image"), eq("https://s3/working"), contains("3. Home 3"));
    verify(whatsapp, times(1)).sendText(any(), any());
  }

  @Test void onlyFirstFiveResultsGetAttachments() {
    results(6);
    for (int i = 1; i <= 5; i++) media("listing" + i,
      file("IMAGE", "READY", "image/jpeg", 100, "https://s3/photo" + i));
    search();
    verify(whatsapp, times(5)).sendMedia(any(), eq("image"), any(), any());
  }
}
