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
import com.milobroker.concierge.service.ConversationRouter;
import com.milobroker.concierge.service.FlowCryptography;
import com.milobroker.concierge.service.InputGuardrailService;
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
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** Flow envelope round-trip plus webhook verification and completion paths. No server needed. */
@ExtendWith(MockitoExtension.class)
class FlowJourneyTest {
  @Mock OnboardingService onboarding;
  @Mock WhatsAppClient whatsapp;
  @Mock ConversationRouter router;
  private KeyPair keys;
  private String pem;
  private FlowCryptography flowsCrypto;
  private FlowController flows;
  private WhatsAppWebhookController webhook;
  private byte[] lastAesKey;
  private String lastIvB64;

  @BeforeEach
  void setup() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    keys = rsa.generateKeyPair();
    pem = "-----BEGIN PRIVATE KEY-----\n"
      + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPrivate().getEncoded())
      + "\n-----END PRIVATE KEY-----\n";
    flowsCrypto = new FlowCryptography(pem, "");
    flows = new FlowController(flowsCrypto, onboarding);
    webhook = new WhatsAppWebhookController("secret-token", onboarding, whatsapp,
      new InputGuardrailService(), router);
  }

  private String envelope(String payloadJson) throws Exception {
    byte[] aesKey = new byte[16];
    byte[] iv = new byte[16];
    SecureRandom random = new SecureRandom();
    random.nextBytes(aesKey);
    random.nextBytes(iv);
    Cipher seal = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
    seal.init(Cipher.ENCRYPT_MODE, keys.getPublic(),
      new javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1",
        java.security.spec.MGF1ParameterSpec.SHA256, javax.crypto.spec.PSource.PSpecified.DEFAULT));
    Cipher body = Cipher.getInstance("AES/GCM/NoPadding");
    body.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
    lastAesKey = aesKey;
    lastIvB64 = Base64.getEncoder().encodeToString(iv);
    return Json.createObjectBuilder()
      .add("encrypted_aes_key", Base64.getEncoder().encodeToString(seal.doFinal(aesKey)))
      .add("initial_vector", lastIvB64)
      .add("encrypted_flow_data", Base64.getEncoder()
        .encodeToString(body.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8))))
      .build().toString();
  }

  /**
   * Replies arrive as raw base64 plaintext per the Flows contract, sealed with
   * the bit-flipped IV; open them with the session key.
   */
  private JsonObject decryptedReply(ResponseEntity<String> response) {
    assertEquals(HttpStatus.OK, response.getStatusCode());
    byte[] iv = Base64.getDecoder().decode(lastIvB64);
    byte[] flipped = new byte[iv.length];
    for (int i = 0; i < flipped.length; i++) {
      flipped[i] = (byte) (iv[i] ^ 0xFF);
    }
    return Jsons.parseObject(flowsCrypto.decryptBody(
      lastAesKey, Base64.getEncoder().encodeToString(flipped), response.getBody()), "Flow reply");
  }

  private String signature(String envelopeJson, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    StringBuilder hex = new StringBuilder("sha256=");
    for (byte b : mac.doFinal(envelopeJson.getBytes(StandardCharsets.UTF_8))) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  private HttpStatus statusOf(ResponseStatusException ex) {
    return (HttpStatus) ex.getStatusCode();
  }

  @Test
  void initReturnsStatesFromAtlas() throws Exception {
    when(onboarding.states()).thenReturn(List.of(new OptionDto("KA", "Karnataka")));
    when(onboarding.profile("user-9")).thenReturn(
      new ProfileDto("user-9", "Asha", null, null, null, false));
    var reply = decryptedReply(flows.exchange(
      envelope("{\"action\":\"INIT\",\"flow_token\":\"user-9\",\"screen\":\"ONBOARDING\"}"), null));
    assertEquals("ONBOARDING", Jsons.string(reply, "screen"));
    assertEquals(1, Jsons.array(Jsons.object(reply, "data"), "states").size());
  }

  @Test
  void stateSelectionReturnsCitiesAndUnknownActionFails() throws Exception {
    when(onboarding.cities("KA")).thenReturn(List.of(new OptionDto("BLR", "Bengaluru")));
    when(onboarding.profile("u")).thenReturn(new ProfileDto("u", null, null, null, null, false));
    var reply = decryptedReply(flows.exchange(envelope(
      "{\"action\":\"data_exchange\",\"screen\":\"ONBOARDING\",\"flow_token\":\"u\",\"data\":{\"state\":\"KA\"}}"),
      null));
    assertEquals(1, Jsons.array(Jsons.object(reply, "data"), "cities").size());
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> flows.exchange(envelope("{\"action\":\"explode\"}"), null));
    assertEquals(HttpStatus.BAD_REQUEST, statusOf(ex));
  }

  @Test
  void backRefreshBehavesLikeInit() throws Exception {
    when(onboarding.profile("user-9")).thenReturn(
      new ProfileDto("user-9", "Asha", null, null, null, false));
    var reply = decryptedReply(flows.exchange(
      envelope("{\"action\":\"BACK\",\"flow_token\":\"user-9\",\"screen\":\"ONBOARDING\"}"), null));
    assertEquals("ONBOARDING", Jsons.string(reply, "screen"));
  }

  @Test
  void errorNotificationIsAcknowledged() throws Exception {
    var reply = decryptedReply(flows.exchange(envelope(
      "{\"action\":\"data_exchange\",\"screen\":\"ONBOARDING\",\"flow_token\":\"u\","
        + "\"data\":{\"error\":\"INVALID_PAYLOAD\",\"error_message\":\"bad field\"}}"), null));
    assertTrue(Jsons.object(reply, "data").getBoolean("acknowledged"));
  }

  @Test
  void undecryptableEnvelopeReturns421() throws Exception {
    String tampered = envelope("{\"action\":\"ping\"}")
      .replaceFirst("\"encrypted_flow_data\":\"[^\"]+\"", "\"encrypted_flow_data\":\"AA\"");
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> flows.exchange(tampered, null));
    assertEquals(HttpStatus.MISDIRECTED_REQUEST, statusOf(ex));
  }

  @Test
  void signedExchangeAcceptedAndTamperedRejected() throws Exception {
    when(onboarding.profile("user-9")).thenReturn(
      new ProfileDto("user-9", "Asha", null, null, null, false));
    FlowController signed = new FlowController(new FlowCryptography(pem, "s3cr3t"), onboarding);
    String env = envelope("{\"action\":\"INIT\",\"flow_token\":\"user-9\",\"screen\":\"ONBOARDING\"}");
    ResponseStatusException missing = assertThrows(ResponseStatusException.class,
      () -> signed.exchange(env, null));
    assertEquals(HttpStatus.FORBIDDEN, statusOf(missing));
    ResponseStatusException wrong = assertThrows(ResponseStatusException.class,
      () -> signed.exchange(env, "sha256=deadbeef"));
    assertEquals(HttpStatus.FORBIDDEN, statusOf(wrong));
    var response = signed.exchange(env, signature(env, "s3cr3t"));
    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void fullPayloadCompletesThroughValidatedPath() throws Exception {
    when(onboarding.complete(eq("user-9"), any(CompletionRequest.class))).thenReturn(
      new ProfileDto("user-9", "Asha", "RENTAL", "KA", "BLR", true));
    var reply = decryptedReply(flows.exchange(envelope(
      "{\"action\":\"data_exchange\",\"screen\":\"ONBOARDING\",\"flow_token\":\"user-9\","
        + "\"data\":{\"name\":\"Asha\",\"intent\":\"Find a rental\",\"state\":\"KA\",\"city\":\"BLR\",\"termsAccepted\":true}}"),
      null));
    assertEquals("COMPLETE", Jsons.string(reply, "screen"));
    verify(onboarding).complete(eq("user-9"), org.mockito.ArgumentMatchers.argThat(
      request -> Boolean.TRUE.equals(request.termsAccepted())));
  }

  @Test
  void completionWebhookForAlreadySavedUserSendsThanksWithoutSavingAgain() {
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", "Asha", "RENTAL", "KA", "BLR", true));
    String payload = "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"messages\":[{\"from\":\"911234567890\",\"type\":\"interactive\","
      + "\"interactive\":{\"nfm_reply\":{\"response_json\":\"{}\"}}}]}"
      + "}]}]}";
    webhook.receive(payload, null);
    verify(onboarding, org.mockito.Mockito.never()).complete(any(), any());
    verify(whatsapp).sendText(eq("911234567890"), contains("Asha"));
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
    when(onboarding.profile("911234567890")).thenReturn(
      new ProfileDto("911234567890", null, null, null, null, false));
    when(onboarding.complete(eq("911234567890"), any(CompletionRequest.class))).thenReturn(
      new ProfileDto("911234567890", "Asha", "RENTAL", "KA", "BLR", true));
    String payload = "{\"entry\":[{\"changes\":[{\"value\":{"
      + "\"contacts\":[{\"profile\":{\"name\":\"Asha\"}}],"
      + "\"messages\":[{\"from\":\"911234567890\",\"type\":\"interactive\","
      + "\"interactive\":{\"type\":\"nfm_reply\",\"nfm_reply\":{\"response_json\":"
      + "\"{\\\"intent\\\":\\\"Find a rental\\\",\\\"state\\\":\\\"KA\\\",\\\"city\\\":\\\"BLR\\\"}\"}}}]}"
      + "}]}]}";
    var response = webhook.receive(payload, null);
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
    var response = webhook.receive(payload, null);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    verify(whatsapp).sendText(eq("911234567890"), contains("Ravi"));
  }
}
