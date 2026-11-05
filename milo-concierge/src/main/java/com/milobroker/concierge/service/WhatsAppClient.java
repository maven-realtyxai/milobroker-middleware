package com.milobroker.concierge.service;
import jakarta.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
/**
 * Sends WhatsApp messages through the Cloud API. When no token is configured
 * (local dev, tests) it logs instead of sending, so nothing depends on Meta.
 * Verifies inbound webhook signatures when an app secret is configured.
 */
@Service
public class WhatsAppClient {
  private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);
  private final RestClient rest;
  private final String apiToken;
  private final String phoneNumberId;
  private final String messagingAccountId;
  private final String onboardingFlowId;
  private final String appSecret;
  public WhatsAppClient(RestClient.Builder builder,
      @Value("${milobroker.whatsapp.api-base-url:https://graph.facebook.com/v21.0}") String baseUrl,
      @Value("${milobroker.whatsapp.api-token:}") String apiToken,
      @Value("${milobroker.whatsapp.phone-number-id:}") String phoneNumberId,
      @Value("${milobroker.whatsapp.messaging-account-id:1057188817349490}") String messagingAccountId,
      @Value("${milobroker.whatsapp.onboarding-flow-id:}") String onboardingFlowId,
      @Value("${milobroker.flow.app-secret:}") String appSecret) {
    this.rest = builder.baseUrl(baseUrl).build();
    this.apiToken = apiToken == null ? "" : apiToken;
    this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId;
    this.messagingAccountId = messagingAccountId == null ? "" : messagingAccountId;
    this.onboardingFlowId = onboardingFlowId == null ? "" : onboardingFlowId;
    this.appSecret = appSecret == null ? "" : appSecret;
  }
  public boolean isConfigured() { return !apiToken.isBlank() && !phoneNumberId.isBlank(); }
  public boolean verifiesWebhookSignatures() { return !appSecret.isBlank(); }
  public boolean isOnboardingFlowConfigured() { return isConfigured() && !onboardingFlowId.isBlank(); }
  public String messagingAccountId() { return messagingAccountId; }
  /**
   * Verifies Meta's X-Hub-Signature-256 header (HMAC-SHA256 of the raw body).
   * Skipped when no app secret is configured, so local dev works untouched.
   */
  public void verifyWebhookSignature(String rawBody, String header) {
    if (appSecret.isBlank()) {
      return;
    }
    if (header == null || !header.startsWith("sha256=")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook request is not signed");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = "sha256="
        + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
      if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
          header.getBytes(StandardCharsets.UTF_8))) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook signature mismatch");
      }
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
        "Cannot verify webhook signature", ex);
    }
  }
  /** Sends the first-contact welcome with the published onboarding Flow button. */
  public boolean sendOnboardingFlow(String to, String displayName) {
    if (!isOnboardingFlowConfigured()) {
      log.warn("WhatsApp onboarding Flow send skipped (API credentials or flow id missing); to={}", to);
      return false;
    }
    String name = displayName == null || displayName.isBlank() ? "there" : displayName.trim();
    String welcome = "Hi " + name
      + ", welcome from MiloBroker. Please fill few more details and open the WhatsApp Flow.";
    String payload = Json.createObjectBuilder()
      .add("messaging_product", "whatsapp")
      .add("recipient_type", "individual")
      .add("to", to)
      .add("type", "interactive")
      .add("interactive", Json.createObjectBuilder()
        .add("type", "flow")
        .add("body", Json.createObjectBuilder().add("text", welcome))
        .add("action", Json.createObjectBuilder()
          .add("name", "flow")
          .add("parameters", Json.createObjectBuilder()
            .add("flow_message_version", "3")
            .add("flow_token", to)
            .add("flow_id", onboardingFlowId)
            .add("flow_cta", "Fill details")
            .add("flow_action", "data_exchange"))))
      .build().toString();
    rest.post().uri("/{id}/messages", phoneNumberId)
      .headers(h -> h.setBearerAuth(apiToken))
      .contentType(MediaType.APPLICATION_JSON)
      .body(payload)
      .retrieve().toBodilessEntity();
    return true;
  }
  /** Sends a media attachment using a fresh signed download URL, never logged. */
  public boolean sendMedia(String to, String type, String url, String caption) {
    if (!"image".equals(type) && !"video".equals(type)) {
      throw new IllegalArgumentException("Only image and video messages are supported");
    }
    java.net.URI link = java.net.URI.create(url);
    if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null) {
      throw new IllegalArgumentException("Media requires an HTTPS URL");
    }
    if (!isConfigured()) {
      log.info("WhatsApp media send skipped (no API credentials); type={}", type);
      return false;
    }
    String text = caption == null ? "" : caption;
    if (text.codePointCount(0, text.length()) > 1024) {
      text = text.substring(0, text.offsetByCodePoints(0, 1024));
    }
    String payload = Json.createObjectBuilder()
      .add("messaging_product", "whatsapp")
      .add("recipient_type", "individual")
      .add("to", to)
      .add("type", type)
      .add(type, Json.createObjectBuilder().add("link", url).add("caption", text))
      .build().toString();
    rest.post().uri("/{id}/messages", phoneNumberId)
      .headers(h -> h.setBearerAuth(apiToken))
      .contentType(MediaType.APPLICATION_JSON).body(payload)
      .retrieve().toBodilessEntity();
    return true;
  }

  /** Sends a plain text message. Returns false when unconfigured (logged only). */
  public boolean sendText(String to, String body) {
    if (!isConfigured()) {
      log.info("WhatsApp send skipped (no API token); to={} body={}", to, body);
      return false;
    }
    String payload = Json.createObjectBuilder()
      .add("messaging_product", "whatsapp")
      .add("to", to)
      .add("type", "text")
      .add("text", Json.createObjectBuilder().add("body", body))
      .build().toString();
    rest.post().uri("/{id}/messages", phoneNumberId)
      .headers(h -> h.setBearerAuth(apiToken))
      .contentType(MediaType.APPLICATION_JSON)
      .body(payload)
      .retrieve().toBodilessEntity();
    return true;
  }
}
