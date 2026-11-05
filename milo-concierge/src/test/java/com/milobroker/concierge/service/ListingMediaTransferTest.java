package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ListingMediaTransferTest {
  @Test void copiesBytesToListingFolderThenConfirmsWithoutLeakingMetaToken() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var whatsapp = mock(WhatsAppMediaClient.class);
    when(whatsapp.download("photo1", 10485760L)).thenReturn(
      new WhatsAppMediaClient.AudioBytes("photo-data".getBytes(StandardCharsets.UTF_8), "image/jpeg"));
    server.expect(requestTo("http://marketplace/api/v1/listings/listing1/media:upload-url"))
      .andExpect(method(HttpMethod.POST))
      .andExpect(content().json("{\"mediaType\":\"IMAGE\",\"contentType\":\"image/jpeg\",\"sizeBytes\":10}"))
      .andRespond(withSuccess("{\"objectKey\":\"listings/listing1/photo.jpg\",\"uploadUrl\":\"https://bucket.s3.amazonaws.com/photo\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://bucket.s3.amazonaws.com/photo"))
      .andExpect(method(HttpMethod.PUT)).andExpect(headerDoesNotExist("Authorization"))
      .andExpect(content().string("photo-data"))
      .andRespond(withSuccess());
    server.expect(requestTo("http://marketplace/api/v1/listings/listing1/media:confirm"))
      .andExpect(content().json("{\"objectKey\":\"listings/listing1/photo.jpg\"}"))
      .andRespond(withSuccess());
    var transfer = new ListingMediaTransfer(whatsapp, builder, "http://marketplace");
    String key = transfer.upload("listing1", "photo1", "image");
    assertEquals("listings/listing1/photo.jpg", key);
    transfer.confirm("listing1", key);
    server.verify();
  }

  @Test void failedS3UploadNeverConfirms() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    var whatsapp = mock(WhatsAppMediaClient.class);
    when(whatsapp.download("v1", 16777216L)).thenReturn(
      new WhatsAppMediaClient.AudioBytes(new byte[]{1, 2}, "video/mp4"));
    server.expect(requestTo("http://marketplace/api/v1/listings/l1/media:upload-url"))
      .andRespond(withSuccess("{\"objectKey\":\"listings/l1/video.mp4\",\"uploadUrl\":\"https://bucket.s3.amazonaws.com/video\"}", MediaType.APPLICATION_JSON));
    server.expect(requestTo("https://bucket.s3.amazonaws.com/video"))
      .andRespond(withServerError());
    var transfer = new ListingMediaTransfer(whatsapp, builder, "http://marketplace");
    assertThrows(org.springframework.web.client.HttpServerErrorException.class,
      () -> transfer.upload("l1", "v1", "video"));
    server.verify();
  }

  @Test void rejectsWrongMimeTypeBeforeRequestingAnUpload() {
    var whatsapp = mock(WhatsAppMediaClient.class);
    when(whatsapp.download("photo1", 10485760L)).thenReturn(
      new WhatsAppMediaClient.AudioBytes(new byte[]{1}, "text/html"));
    var transfer = new ListingMediaTransfer(whatsapp, RestClient.builder(), "http://marketplace");
    assertThrows(IllegalArgumentException.class, () -> transfer.upload("l1", "photo1", "image"));
  }
}
