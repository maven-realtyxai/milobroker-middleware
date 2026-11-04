package com.milobroker.concierge.api;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.ChatOnboardingService;
import com.milobroker.concierge.service.ConversationRouter;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.WhatsAppClient;
import com.milobroker.concierge.service.UserDeletionService;
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
  private final InputGuardrailService guardrail;
  private final ConversationRouter router;
  private final UserDeletionService deletion;
  private final ChatOnboardingService chat;
  @org.springframework.beans.factory.annotation.Autowired
  public WhatsAppWebhookController(
      @Value("${milobroker.whatsapp.verify-token:change-me}") String verifyToken,
      OnboardingService onboarding, WhatsAppClient whatsapp, InputGuardrailService guardrail,
      ConversationRouter router, UserDeletionService deletion, ChatOnboardingService chat) {
    this.verifyToken = verifyToken;
    this.onboarding = onboarding;
    this.whatsapp = whatsapp;
    this.guardrail = guardrail;
    this.router = router;
    this.deletion = deletion;
    this.chat = chat;
  }
  public WhatsAppWebhookController(String verifyToken, OnboardingService onboarding,
      WhatsAppClient whatsapp, InputGuardrailService guardrail, ConversationRouter router,
      UserDeletionService deletion) {
    this(verifyToken, onboarding, whatsapp, guardrail, router, deletion, null);
  }
  public WhatsAppWebhookController(String verifyToken, OnboardingService onboarding,
      WhatsAppClient whatsapp, InputGuardrailService guardrail, ConversationRouter router) {
    this(verifyToken, onboarding, whatsapp, guardrail, router, null, null);
  }
  public WhatsAppWebhookController(String verifyToken, OnboardingService onboarding,
      WhatsAppClient whatsapp, InputGuardrailService guardrail, ConversationRouter router,
      ChatOnboardingService chat) {
    this(verifyToken, onboarding, whatsapp, guardrail, router, null, chat);
  }
  @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
  public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
      @RequestParam(name = "hub.verify-token", required = false) String token,
      @RequestParam(name = "hub.challenge", required = false) String challenge) {
    if ("subscribe".equals(mode) && verifyToken.equals(token) && challenge != null) {
      return ResponseEntity.ok(challenge);
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Webhook verification failed");
  }
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Void> receive(@RequestBody String payloadJson,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
    whatsapp.verifyWebhookSignature(payloadJson, signature);
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
        String messageType = Jsons.string(message, "type");
        String body = Jsons.string(Jsons.object(message, "text"), "body");
        if (handleDeletion(from, messageType, body)) continue;
        if (onboarding.profile(from).completed()) {
          router.reply(from, senderName, body, messageType);
        } else {
          chatOrWelcome(from, senderName, messageType, body);
        }
      }
    }
  }
  private boolean handleDeletion(String from, String messageType, String body) {
    if (!"text".equals(messageType) || body == null) return false;
    String command = body.trim().toUpperCase(java.util.Locale.ROOT);
    if (!command.equals("DELETE MY DATA") && !command.equals("CANCEL DELETE")
        && !command.startsWith("CONFIRM DELETE ")) return false;
    if (deletion == null || !whatsapp.verifiesWebhookSignatures()) {
      whatsapp.sendText(from, "Data deletion is not available right now. Please contact MiloBroker support.");
      return true;
    }
    try {
      switch (command.startsWith("CONFIRM DELETE ") ? "CONFIRM DELETE" : command) {
        case "DELETE MY DATA" -> {
          String code = deletion.request(from);
          whatsapp.sendText(from, "This will permanently delete your profile, property posts and related data. "
            + "Reply CONFIRM DELETE " + code + " within 15 minutes, or CANCEL DELETE to keep them.");
        }
        case "CANCEL DELETE" -> {
          deletion.cancel(from);
          whatsapp.sendText(from, "Your deletion request has been cancelled.");
        }
        case "CONFIRM DELETE" -> {
          if (deletion.confirm(from, command.substring("CONFIRM DELETE ".length()).trim())) {
            whatsapp.sendText(from, "Your MiloBroker profile, property posts and related stored data have been deleted.");
          } else {
            whatsapp.sendText(from, "No active deletion request was found. Send DELETE MY DATA to start.");
          }
        }
        default -> { }
      }
    } catch (Exception ex) {
      log.warn("User data deletion could not be completed", ex);
      whatsapp.sendText(from, "We could not complete your deletion request. Please try again later or contact MiloBroker support.");
    }
    return true;
  }
  private void completeFromFlow(String from, String senderName, String responseJson) {
    try {
      ProfileDto current = onboarding.profile(from);
      if (current.completed()) {
        whatsapp.sendText(from, "Thanks " + current.displayName()
          + "! Your onboarding is complete. Tell me what you are looking for.");
        return;
      }
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
        role, Jsons.string(data, "state"), Jsons.string(data, "city"),
        jakarta.json.JsonValue.TRUE.equals(data.get("termsAccepted")));
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
  /** Incomplete users go through the plain-text slot machine; the legacy plain welcome is the fallback. */
  private void chatOrWelcome(String from, String senderName, String messageType, String text) {
    if (chat != null) {
      chat.handle(from, senderName, text, messageType);
      return;
    }
    welcomeWhenIncomplete(from, senderName, messageType, text);
  }
  private void welcomeWhenIncomplete(String from, String senderName, String messageType, String text) {
    ProfileDto profile = onboarding.profile(from);
    if (!profile.completed()) {
      String name = senderName != null && !senderName.isBlank()
        ? senderName : profile.displayName();
      String greet = name != null && !name.isBlank() ? name : "there";
      InputGuardrailService.Verdict verdict = InputGuardrailService.isVoiceType(messageType)
        ? new InputGuardrailService.Verdict(InputGuardrailService.Decision.VOICE, null)
        : guardrail.evaluateText(text);
      if (!verdict.allowed()) {
        whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
        return;
      }
      if (verdict.decision() == InputGuardrailService.Decision.VOICE) {
        whatsapp.sendText(from, "Hi " + greet + "! I can't listen to voice notes yet - "
          + "please type your message and I'll reply.");
        return;
      }
      whatsapp.sendText(from, "Hi " + greet + "! Welcome to MiloBroker. "
        + "Tell me what you are looking for - a rental, a purchase, or listing your property.");
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
