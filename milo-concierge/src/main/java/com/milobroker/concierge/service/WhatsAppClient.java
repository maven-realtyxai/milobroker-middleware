package com.milobroker.concierge.service;
import jakarta.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
/**
 * Sends WhatsApp messages through the Cloud API. When no token is configured
 * (local dev, tests) it logs instead of sending, so nothing depends on Meta.
 */
@Service
public class WhatsAppClient {
  private static final Logger log = LoggerFactory.getLogger(WhatsAppClient.class);
  private final RestClient rest;
  private final String apiToken;
  private final String phoneNumberId;
  private final String onboardingFlowId;
  public WhatsAppClient(RestClient.Builder builder,
      @Value("${milobroker.whatsapp.api-base-url:https://graph.facebook.com/v21.0}") String baseUrl,
      @Value("${milobroker.whatsapp.api-token:}") String apiToken,
      @Value("${milobroker.whatsapp.phone-number-id:}") String phoneNumberId,
      @Value("${milobroker.whatsapp.onboarding-flow-id:}") String onboardingFlowId) {
    this.rest = builder.baseUrl(baseUrl).build();
    this.apiToken = apiToken == null ? "" : apiToken;
    this.phoneNumberId = phoneNumberId == null ? "" : phoneNumberId;
    this.onboardingFlowId = onboardingFlowId == null ? "" : onboardingFlowId;
  }
  public boolean isConfigured() { return !apiToken.isBlank() && !phoneNumberId.isBlank(); }
  public boolean isOnboardingFlowConfigured() { return isConfigured() && !onboardingFlowId.isBlank(); }
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
