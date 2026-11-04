package com.milobroker.concierge.service;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
/**
 * Meta Flows data-exchange encryption. WhatsApp encrypts each request with a
 * fresh AES-128 key (itself RSA-OAEP sealed with our uploaded public key);
 * responses are AES-128-GCM sealed with the same key and a bit-flipped IV.
 * Optional X-Hub-Signature-256 verification when an app secret is configured.
 * Pure JDK crypto, no extra dependency.
 */
@Service
public class FlowCryptography {
  private static final int GCM_TAG_BITS = 128;
  private final PrivateKey privateKey;
  private final String appSecret;
  public FlowCryptography(@Value("${milobroker.flow.private-key-pem:}") String pem,
      @Value("${milobroker.flow.app-secret:}") String appSecret) {
    this.privateKey = pem == null || pem.isBlank() ? null : loadPrivateKey(pem);
    this.appSecret = appSecret == null || appSecret.isBlank() ? null : appSecret;
  }
  public boolean isConfigured() { return privateKey != null; }
  /**
   * Verifies Meta's X-Hub-Signature-256 header (HMAC-SHA256 of the raw body).
   * Skipped when no app secret is configured, so local dev works untouched.
   */
  public void verifySignature(String rawBody, String header) {
    if (appSecret == null) {
      return;
    }
    if (header == null || !header.startsWith("sha256=")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Flow request is not signed");
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      String expected = "sha256="
        + HexFormat.of().formatHex(mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8)));
      if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
          header.getBytes(StandardCharsets.UTF_8))) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Flow signature mismatch");
      }
    } catch (ResponseStatusException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
        "Cannot verify Flow signature", ex);
    }
  }
  /** Decrypts one AES session key sealed with our RSA public key. */
  public byte[] decryptAesKey(String base64SealedKey) {
    try {
      Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      // Meta uses SHA-256 for both OAEP and MGF1. The JDK's provider default
      // for MGF1 can otherwise be SHA-1 even when the transformation says SHA-256.
      rsa.init(Cipher.DECRYPT_MODE, privateKey,
        new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      return rsa.doFinal(Base64.getDecoder().decode(base64SealedKey));
    } catch (Exception ex) {
      // 421: Meta re-downloads the public key and retries the request.
      throw new ResponseStatusException(HttpStatus.MISDIRECTED_REQUEST,
        "Cannot unseal Flow session key", ex);
    }
  }
  /** Decrypts the request body with the session key and the given IV. */
  public String decryptBody(byte[] aesKey, String base64Iv, String base64Ciphertext) {
    try {
      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
        new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(base64Iv)));
      return new String(aes.doFinal(Base64.getDecoder().decode(base64Ciphertext)), StandardCharsets.UTF_8);
    } catch (Exception ex) {
      // 421: Meta re-downloads the public key and retries the request.
      throw new ResponseStatusException(HttpStatus.MISDIRECTED_REQUEST,
        "Cannot decrypt Flow payload", ex);
    }
  }
  /** Encrypts the response body with the session key and the bit-flipped IV. */
  public EncryptedResponse encryptBody(byte[] aesKey, String base64Iv, String plaintext) {
    try {
      byte[] iv = Base64.getDecoder().decode(base64Iv);
      byte[] flipped = new byte[iv.length];
      for (int i = 0; i < iv.length; i++) {
        flipped[i] = (byte) (iv[i] ^ 0xFF);
      }
      Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
        new GCMParameterSpec(GCM_TAG_BITS, flipped));
      String sealed = Base64.getEncoder()
        .encodeToString(aes.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
      return new EncryptedResponse(sealed);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot seal Flow response", ex);
    }
  }
  public record EncryptedResponse(String encryptedFlowData) {}
  private static PrivateKey loadPrivateKey(String pem) {
    String hint = "paste the full flow-private-pkcs8.pem contents on one line";
    if (pem.contains("RSA PRIVATE KEY")) {
      hint = "this is a PKCS#1 key; convert it with: openssl pkcs8 -topk8 -inform PEM -outform PEM -in flow-private.pem -out flow-private-pkcs8.pem -nocrypt";
    } else if (pem.contains("PUBLIC KEY")) {
      hint = "this is a public key; paste the private half (flow-private-pkcs8.pem)";
    }
    try {
      String body = pem.replace("\\n", "\n").replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(body);
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception ex) {
      throw new IllegalStateException("FLOW_PRIVATE_KEY_PEM is not a valid PKCS#8 RSA key: " + hint, ex);
    }
  }
}
