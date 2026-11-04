package com.milobroker.concierge.service;

import com.milobroker.concierge.json.Jsons;
import jakarta.json.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Downloads incoming WhatsApp voice-note bytes through the Cloud API media
 * endpoints. First resolves the media id to a short-lived download URL, then
 * fetches the raw bytes with an application-side size cap. Every failure
 * surfaces as an HTTP-mapped exception the caller converts to a graceful
 * text fallback, so oversized or deleted voice notes never break the chat.
 */
@Service
public class WhatsAppMediaClient {
  private final RestClient rest;
  private final String apiToken;
  private final long maxBytes;

  public WhatsAppMediaClient(RestClient.Builder builder,
      @Value("${milobroker.whatsapp.api-base-url:https://graph.facebook.com/v21.0}") String baseUrl,
      @Value("${milobroker.whatsapp.api-token:}") String apiToken,
      @Value("${milobroker.voice.max-bytes:10485760}") long maxBytes) {
    this.rest = builder.baseUrl(baseUrl).build();
    this.apiToken = apiToken == null ? "" : apiToken;
    this.maxBytes = maxBytes;
  }

  public boolean isConfigured() {
    return !apiToken.isBlank();
  }

  public record AudioBytes(byte[] data, String mimeType) {
  }

  public AudioBytes downloadAudio(String mediaId) {
    if (!isConfigured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "WhatsApp media download is not configured");
    }
    String info;
    try {
      info = rest.get().uri("/{id}", mediaId)
          .headers(h -> h.setBearerAuth(apiToken))
          .retrieve().body(String.class);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "WhatsApp media lookup failed", ex);
    }
    JsonObject root = Jsons.parseObject(info == null ? "{}" : info, "Media info");
    String downloadUrl = Jsons.string(root, "download_url");
    if (downloadUrl == null || downloadUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "WhatsApp media has no download URL");
    }
    String mime = Jsons.string(root, "mime_type");
    byte[] data;
    try {
      data = rest.get().uri(java.net.URI.create(downloadUrl))
          .headers(h -> h.setBearerAuth(apiToken))
          .retrieve().body(byte[].class);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "WhatsApp media download failed", ex);
    }
    if (data == null || data.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "WhatsApp media was empty");
    }
    if (data.length > maxBytes) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
          "Voice note is too large, please send a shorter one");
    }
    if (mime == null || mime.isBlank()) {
      mime = "application/octet-stream";
    }
    return new AudioBytes(data, mime);
  }
}
