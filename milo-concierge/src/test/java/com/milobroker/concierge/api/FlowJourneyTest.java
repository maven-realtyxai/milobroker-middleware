package com.milobroker.concierge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.OptionDto;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.FlowCryptography;
import com.milobroker.concierge.service.OnboardingService;
import com.milobroker.concierge.service.WhatsAppClient;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Flow envelope round-trip plus webhook verification and completion paths. No server needed. */
@ExtendWith(MockitoExtension.class)
class FlowJourneyTest {
  @Mock OnboardingService onboarding;
  @Mock WhatsAppClient whatsapp;
  private KeyPair keys;
  private FlowController flows;
  private WhatsAppWebhookController webhook;

  @BeforeEach
  void setup() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    keys = rsa.generateKeyPair();
    String pem = "-----BEGIN PRIVATE KEY-----\n"
      + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPrivate().getEncoded())
      + "\n-----END PRIVATE KEY-----\n";
    flows = new FlowController(new FlowCryptography(pem), onboarding);
    webhook = new WhatsAppWebhookController("secret-token", onboarding, whatsapp);
  }

  private String envelope(String payloadJson) throws Exception {
    byte[] aesKey = new byte[16];
    byte[] iv = new byte[12];
    SecureRandom random = new SecureRandom();
    random.nextBytes(aesKey);
    random.nextBytes(iv);
    Cipher seal = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
    seal.init(Cipher.ENCRYPT_MODE, keys.getPublic());
    Cipher body = Cipher.getInstance("AES/GCM/NoPadding");
    body.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
    return Json.createObjectBuilder()
      .add("encrypted_aes_key", Base64.getEncoder().encodeToString(seal.doFinal(aesKey)))
      .add("initial_vector", Base64.getEncoder().encodeToString(iv))
      .add("encrypted_flow_data", Base64.getEncoder()
        .encodeToString(body.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8))))
      .build().toString();
  }

  @Test
  void initReturnsStatesFromAtlas() throws Exception {
    when(onboarding.states()).thenReturn(List.of(new OptionDto("KA", "Karnataka")));
    when(onboarding.profile("user-9")).thenReturn(
      new ProfileDto("user-9", "Asha", null, null, null, false));
    var response = flows.exchange(
      envelope("{\"action\":\"INIT\",\"flow_token\":\"user-9\",\"screen\":\"ONBOARDING\"}"));
    assertEquals(HttpStatus.OK, response.getStatusCode());
    JsonObject sealed = Jsons.parseObject(response.getBody(), "Flow reply");
    assertTrue(sealed.containsKey("encrypted_flow_data"));
  }

  @Test
  void stateSelectionReturnsCitiesAndUnknownActionFails() throws Exception {
    when(onboarding.cities("KA")).thenReturn(List.of(new OptionDto("BLR", "Bengaluru")));
    var response = flows.exchange(envelope(
      "{\"action\":\"data_exchange\",\"screen\":\"ONBOARDING\",\"flow_token\":\"u\",\"data\":{\"state\":\"KA\"}}"));
    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertThrows(ResponseStatusException.class,
      () -> flows.exchange(envelope("{\"action\":\"explode\"}")));
  }

  @Test
  void fullPayloadCompletesThroughValidatedPath() throws Exception {
    when(onboarding.complete(eq("user-9"), any(CompletionRequest.class))).thenReturn(
      new ProfileDto("user-9", "Asha", "RENTAL", "KA", "BLR", true));
    var response = flows.exchange(envelope(
      "{\"action\":\"data_exchange\",\"screen\":\"ONBOARDING\",\"flow_token\":\"user-9\","
        + "\"data\":{\"name\":\"Asha\",\"intent\":\"Find a rental\",\"state\":\"KA\",\"city\":\"BLR\"}}"));
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(onboarding).complete(eq("user-9"), any(CompletionRequest.class));
  }

  @Test
  void webhookVerifiesChallenge() {
    var ok = webhook.verify("subscribe", "secret-token", "challenge-123");
    assertEquals("challenge-123", ok.getBody());
    assertThrows(ResponseStatusException.class,
      () -> webhook.verify("subscribe", "wrong", "challenge-123"));
  }

  @Test
  void webhookCompletesFlowResponseAndGreetsByName() {
    when(onboarding.complete(eq("911234567890"), any(CompletionRequest.class))).thenReturn(
      new ProfileDto("911234567890", "Asha", "RENTAL", "KA", "BLR", true));
    String payload = "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"Asha\"}}],"
      + "\"messages\":[{\"from\":\"911234567890\",\"type\":\"interactive\","
      + "\"interactive\":{\"type\":\"nfm_reply\",\"nfm_reply\":{\"response_json\":"
      + "\"{\\\"intent\\\":\\\"Find a rental\\\",\\\"state\\\":\\\"KA\\\",\\\"city\\\":\\\"BLR\\\"}\"}}}]}"
      + "}]}]}";
    var response = webhook.receive(payload);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(onboarding).complete(eq("911234567890"), any(CompletionRequest.class));
    verify(whatsapp).sendText(eq("911234567890"), contains("Asha"));
  }

  @Test
  void webhookNudgesIncompleteTextSenders() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", null, null, null, null, false));
    String payload = "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"Ravi\"}}],"
      + "\"messages\":[{\"from\":\"911234567890\",\"type\":\"text\",\"text\":{\"body\":\"hi\"}}]}}]}]}";
    var response = webhook.receive(payload);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(whatsapp).sendText(eq("911234567890"), contains("Ravi"));
  }
}
