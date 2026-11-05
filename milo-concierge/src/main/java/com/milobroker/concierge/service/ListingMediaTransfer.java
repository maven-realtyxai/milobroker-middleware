package com.milobroker.concierge.service;

import com.milobroker.concierge.json.Jsons;
import jakarta.json.Json;
import java.net.URI;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** Copies WhatsApp attachments into the private listing folder using Marketplace's signer. */
@Service
public class ListingMediaTransfer {
  private final WhatsAppMediaClient whatsapp;
  private final RestClient marketplace;
  private final RestClient storage;

  public ListingMediaTransfer(WhatsAppMediaClient whatsapp, RestClient.Builder builder,
      @Value("${milobroker.marketplace.base-url:${milobroker.downstream.marketplace-base-url:http://localhost:8082}}") String baseUrl) {
    this.whatsapp = whatsapp;
    this.marketplace = builder.clone().baseUrl(baseUrl).build();
    // A separate client: never forward the WhatsApp bearer token to S3.
    this.storage = builder.clone().build();
  }

  public String upload(String listingId, String mediaId, String type) {
    if (!"image".equals(type) && !"video".equals(type)) throw new IllegalArgumentException("Unsupported attachment");
    var file = whatsapp.download(mediaId, "image".equals(type) ? 10 * 1024 * 1024L : 16 * 1024 * 1024L);
    if (!file.mimeType().toLowerCase(Locale.ROOT).startsWith(type + "/")) {
      throw new IllegalArgumentException("Attachment content type does not match message type");
    }
    String payload = Json.createObjectBuilder().add("mediaType", type.toUpperCase(Locale.ROOT))
      .add("contentType", file.mimeType()).add("sizeBytes", file.data().length).build().toString();
    String json = marketplace.post().uri("/api/v1/listings/{id}/media:upload-url", listingId)
      .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(String.class);
    var target = Jsons.parseObject(json, "Listing media upload");
    String key = Jsons.string(target, "objectKey");
    URI url = URI.create(Jsons.string(target, "uploadUrl"));
    if (key == null || !key.startsWith("listings/" + listingId + "/") || !"https".equals(url.getScheme())) {
      throw new IllegalStateException("Invalid listing upload destination");
    }
    storage.put().uri(url).contentType(MediaType.parseMediaType(file.mimeType()))
      .contentLength(file.data().length).body(file.data()).retrieve().toBodilessEntity();
    return key;
  }

  public void confirm(String listingId, String objectKey) {
    marketplace.post().uri("/api/v1/listings/{id}/media:confirm", listingId)
      .contentType(MediaType.APPLICATION_JSON)
      .body(Json.createObjectBuilder().add("objectKey", objectKey).build().toString())
      .retrieve().toBodilessEntity();
  }
}
