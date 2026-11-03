package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * Simulates Meta's side with raw JCE, then runs our side. Proves the wire
 * format matches: RSA-OAEP sealed AES key, AES-128-GCM bodies, flipped-IV replies.
 */
class FlowCryptographyTest {
  private FlowCryptography crypto;
  private KeyPair keys;

  @BeforeEach
  void keys() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(2048);
    keys = rsa.generateKeyPair();
    String pem = "-----BEGIN PRIVATE KEY-----\n"
      + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPrivate().getEncoded())
      + "\n-----END PRIVATE KEY-----\n";
    crypto = new FlowCryptography(pem);
  }

  @Test
  void metaRoundTrip() throws Exception {
    byte[] aesKey = new byte[16];
    new java.security.SecureRandom().nextBytes(aesKey);
    byte[] iv = new byte[12];
    new java.security.SecureRandom().nextBytes(iv);
    Cipher seal = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
    seal.init(Cipher.ENCRYPT_MODE, keys.getPublic());
    String sealedKey = Base64.getEncoder().encodeToString(seal.doFinal(aesKey));
    Cipher body = Cipher.getInstance("AES/GCM/NoPadding");
    body.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
    String sealedBody = Base64.getEncoder().encodeToString(
      body.doFinal("{\"action\":\"ping\"}".getBytes(StandardCharsets.UTF_8)));
    String ivB64 = Base64.getEncoder().encodeToString(iv);
    assertArrayEquals(aesKey, crypto.decryptAesKey(sealedKey));
    assertEquals("{\"action\":\"ping\"}", crypto.decryptBody(aesKey, ivB64, sealedBody));
    var reply = crypto.encryptBody(aesKey, ivB64, "{\"version\":\"3.0\"}");
    Cipher open = Cipher.getInstance("AES/GCM/NoPadding");
    byte[] flipped = iv.clone();
    for (int i = 0; i < flipped.length; i++) {
      flipped[i] = (byte) (flipped[i] ^ 0xFF);
    }
    open.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, flipped));
    assertEquals("{\"version\":\"3.0\"}",
      new String(open.doFinal(Base64.getDecoder().decode(reply.encryptedFlowData())), StandardCharsets.UTF_8));
  }

  @Test
  void tamperedBodyIsRejected() {
    assertThrows(ResponseStatusException.class,
      () -> crypto.decryptBody(new byte[16], Base64.getEncoder().encodeToString(new byte[12]), "!!!"));
  }
}
