package com.milobroker.concierge.api;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.OptionDto;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.FlowCryptography;
import com.milobroker.concierge.service.OnboardingService;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/**
 * Meta WhatsApp Flows data-exchange endpoint. Must be publicly reachable over
 * HTTPS. Handles ping health checks, INIT (state options from Atlas),
 * state selection (city options, clearing any prior city client-side) and
 * full-payload completion through the same validated path as the webhook.
 * Speaks pure JSON-P; no Jackson anywhere in the service.
 */
@RestController @RequestMapping("/api/v1/flows/onboarding")
public class FlowController {
  private static final String VERSION = "3.0";
  private static final String SCREEN = "ONBOARDING";
  private static final String COMPLETE_SCREEN = "COMPLETE";
  private final FlowCryptography crypto;
  private final OnboardingService onboarding;
  public FlowController(FlowCryptography crypto, OnboardingService onboarding) {
    this.crypto = crypto;
    this.onboarding = onboarding;
  }
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> exchange(@RequestBody String envelopeJson) {
    if (!crypto.isConfigured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Flow endpoint has no private key configured");
    }
    JsonObject envelope = Jsons.parseObject(envelopeJson, "Flow envelope");
    byte[] aesKey = crypto.decryptAesKey(required(envelope, "encrypted_aes_key"));
    String iv = required(envelope, "initial_vector");
    String payload = crypto.decryptBody(aesKey, iv, required(envelope, "encrypted_flow_data"));
    String response = dispatch(Jsons.parseObject(payload, "Flow payload"));
    String sealed = crypto.encryptBody(aesKey, iv, response).encryptedFlowData();
    return ResponseEntity.ok(Json.createObjectBuilder()
      .add("encrypted_flow_data", sealed).build().toString());
  }
  private String dispatch(JsonObject request) {
    String action = Jsons.string(request, "action");
    if ("ping".equals(action)) {
      return status();
    }
    if ("INIT".equals(action)) {
      return init(request);
    }
    if ("data_exchange".equals(action)) {
      return dataExchange(request);
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
      action == null ? "Flow action is missing" : "Unknown Flow action: " + action);
  }
  private String status() {
    return Json.createObjectBuilder().add("version", VERSION)
      .add("data", Json.createObjectBuilder().add("status", "active")).build().toString();
  }
  private String init(JsonObject request) {
    String userId = Jsons.string(request, "flow_token");
    String displayName = request.containsKey("data")
      ? Jsons.string(Jsons.object(request, "data"), "displayName") : null;
    if (displayName == null || displayName.isBlank()) {
      displayName = displayName(userId);
    }
    JsonObjectBuilder data = screenData(displayName, null);
    return Json.createObjectBuilder().add("version", VERSION)
      .add("screen", SCREEN).add("data", data).build().toString();
  }
  private String dataExchange(JsonObject request) {
    JsonObject data = Jsons.object(request, "data");
    String userId = Jsons.string(request, "flow_token");
    if (isCompletePayload(data)) {
      CompletionRequest completion = new CompletionRequest(
        Jsons.string(data, "name"), roleValue(data),
        Jsons.string(data, "state"), Jsons.string(data, "city"));
      onboarding.complete(userId, completion);
      JsonObjectBuilder done = Json.createObjectBuilder().add("success", true)
        .add("message", "Onboarding complete. Welcome, " + completion.displayName().trim() + "!");
      return Json.createObjectBuilder().add("version", VERSION).add("screen", COMPLETE_SCREEN)
        .add("data", done).build().toString();
    }
    String stateCode = data == null ? null : Jsons.string(data, "state");
    String name = data == null ? null : Jsons.string(data, "name");
    if (name == null || name.isBlank()) {
      name = displayName(userId);
    }
    JsonObjectBuilder response = screenData(name, stateCode);
    return Json.createObjectBuilder().add("version", VERSION).add("screen", SCREEN)
      .add("data", response).build().toString();
  }
  private boolean isCompletePayload(JsonObject data) {
    return data != null && Jsons.string(data, "name") != null
      && roleValue(data) != null && Jsons.string(data, "state") != null
      && Jsons.string(data, "city") != null;
  }
  private String roleValue(JsonObject data) {
    String role = Jsons.string(data, "role");
    return role == null ? Jsons.string(data, "intent") : role;
  }
  private JsonObjectBuilder screenData(String displayName, String stateCode) {
    JsonArrayBuilder cities = Json.createArrayBuilder();
    if (stateCode != null && !stateCode.isBlank()) {
      for (OptionDto city : onboarding.cities(stateCode)) {
        cities.add(Json.createObjectBuilder().add("id", city.id()).add("title", city.title()));
      }
    }
    return Json.createObjectBuilder()
      .add("greeting", "Hi " + displayName + ", welcome to MiloBroker")
      .add("displayName", displayName)
      .add("roles", options(onboarding.roles()))
      .add("states", options(onboarding.states()))
      .add("cities", cities);
  }
  private JsonArrayBuilder options(java.util.List<OptionDto> options) {
    JsonArrayBuilder array = Json.createArrayBuilder();
    for (OptionDto option : options) {
      array.add(Json.createObjectBuilder().add("id", option.id()).add("title", option.title()));
    }
    return array;
  }
  private String displayName(String userId) {
    String name = onboarding.profile(userId).displayName();
    return name == null || name.isBlank() ? "there" : name;
  }
  private String required(JsonObject envelope, String field) {
    String value = Jsons.string(envelope, field);
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Flow envelope misses " + field);
    }
    return value;
  }
}
