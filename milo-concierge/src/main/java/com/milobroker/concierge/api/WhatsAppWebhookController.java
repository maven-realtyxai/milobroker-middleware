package com.milobroker.concierge.api;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.WhatsAppClient;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/**
 * WhatsApp Cloud API webhook. GET verifies the subscription with Meta;
 * POST receives messages and completed Flow responses. The sender id comes
 * from WhatsApp itself, so the phone number is never a form field.
 * Always answers 200 once the envelope parses: Meta retries anything else.
 * Speaks pure JSON-P; no Jackson anywhere in the service.
 */
@RestController @RequestMapping("/api/v1/whatsapp/webhook")
public class WhatsAppWebhookController {
  private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
  private final String verifyToken;
  private final OnboardingService onboarding;
  private final WhatsAppClient whatsapp;
  public WhatsAppWebhookController(
      @Value("${milobroker.whatsapp.verify-token:change-me}") String verifyToken,
      OnboardingService onboarding, WhatsAppClient whatsapp) {
    this.verifyToken = verifyToken;
    this.onboarding = onboarding;
    this.whatsapp = whatsapp;
  }
  @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
      @RequestParam(name = "hub.verify_token", required = false) String token,
      @RequestParam(name = "hub.challenge", required = false) String challenge) {
    if ("subscribe".equals(mode) && verifyToken.equals(token) && challenge != null) {
      return ResponseEntity.ok(challenge);
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook verification failed");
  }
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> receive(@RequestBody String payloadJson) {
    JsonObject payload = Jsons.parseObject(payloadJson, "Webhook payload");
    for (JsonObject entry : objects(Jsons.array(payload, "entry"))) {
      for (JsonObject change : objects(Jsons.array(entry, "changes"))) {
        handleValue(Jsons.object(change, "value"));
      }
    }
    return ResponseEntity.ok().build();
  }
  /** Ops check used before sending anything: is this user onboarded? */
  @GetMapping("/profile")
  public ResponseEntity<String> profile(@RequestParam String channelUserId) {
    ProfileDto p = onboarding.profile(channelUserId);
    return ResponseEntity.ok("{"
      + "\"channelUserId\":" + quote(p.channelUserId()) + ","
      + "\"displayName\":" + quote(p.displayName()) + ","
      + "\"intent\":" + quote(p.intent()) + ","
      + "\"stateCode\":" + quote(p.stateCode()) + ","
      + "\"cityCode\":" + quote(p.cityCode()) + ","
      + "\"completed\":" + p.completed()
      + "}");
  }
  private void handleValue(JsonObject value) {
    if (value == null) {
      return;
    }
    String senderName = null;
    for (JsonObject contact : objects(Jsons.array(value, "contacts"))) {
      senderName = Jsons.string(Jsons.object(contact, "profile"), "name");
    }
    for (JsonObject message : objects(Jsons.array(value, "messages"))) {
      String from = Jsons.string(message, "from");
      if (from == null || from.isBlank()) {
        continue;
      }
      String flowReply = Jsons.string(
        Jsons.object(Jsons.object(message, "interactive"), "nfm_reply"), "response_json");
      if (flowReply != null) {
        completeFromFlow(from, senderName, flowReply);
      } else {
        welcomeWhenIncomplete(from, senderName);
      }
    }
  }
  private void completeFromFlow(String from, String senderName, String responseJson) {
    try {
      JsonObject data = Jsons.parseObject(responseJson, "Flow response");
      String name = Jsons.string(data, "name");
      if (name == null || name.isBlank()) {
        name = senderName;
      }
      String role = Jsons.string(data, "role");
      if (role == null) {
        role = Jsons.string(data, "intent");
      }
      CompletionRequest completion = new CompletionRequest(name,
        role, Jsons.string(data, "state"), Jsons.string(data, "city"));
      ProfileDto saved = onboarding.complete(from, completion);
      whatsapp.sendText(from, "Thanks " + saved.displayName()
        + "! Your onboarding is complete. Tell me what you are looking for.");
    } catch (ResponseStatusException ex) {
      log.warn("Rejected Flow completion from {}: {}", from, ex.getReason());
      whatsapp.sendText(from, "I could not save that: " + ex.getReason()
        + ". Please open the form again and check each field.");
    } catch (Exception ex) {
      log.warn("Unparseable Flow response from {}", from, ex);
    }
  }
  private void welcomeWhenIncomplete(String from, String senderName) {
    ProfileDto profile = onboarding.profile(from);
    if (!profile.completed()) {
      String name = senderName != null && !senderName.isBlank()
        ? senderName : profile.displayName();
      if (whatsapp.isOnboardingFlowConfigured() && onboarding.claimWelcome(from, name)) {
        try {
          if (whatsapp.sendOnboardingFlow(from, name)) {
            return;
          }
        } catch (Exception ex) {
          log.warn("Could not send onboarding Flow to {}", from, ex);
        }
        try {
          onboarding.releaseWelcome(from);
        } catch (Exception ex) {
          log.warn("Could not release onboarding welcome claim for {}", from, ex);
        }
      }
    }
  }
  private static java.util.List<JsonObject> objects(JsonArray array) {
    java.util.List<JsonObject> out = new java.util.ArrayList<>(array.size());
    for (jakarta.json.JsonValue value : array) {
      if (value instanceof JsonObject obj) {
        out.add(obj);
      }
    }
    return out;
  }
  private static String quote(String value) {
    if (value == null) {
      return "null";
    }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
