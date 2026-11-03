package com.milobroker.concierge.service;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
/**
 * Meta Flows data-exchange encryption. WhatsApp encrypts each request with a
 * fresh AES-128 key (itself RSA-OAEP sealed with our uploaded public key);
 * responses are AES-128-GCM sealed with the same key and a bit-flipped IV.
 * Pure JDK crypto, no extra dependency.
 */
@Service
public class FlowCryptography {
  private static final int GCM_TAG_BITS = 128;
  private final PrivateKey privateKey;
  public FlowCryptography(@Value("${milobroker.flow.private-key-pem:}") String pem) {
    this.privateKey = pem == null || pem.isBlank() ? null : loadPrivateKey(pem);
  }
  public boolean isConfigured() { return privateKey != null; }
  /** Decrypts one AES session key sealed with our RSA public key. */
  public byte[] decryptAesKey(String base64SealedKey) {
    try {
      Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      rsa.init(Cipher.DECRYPT_MODE, privateKey);
      return rsa.doFinal(Base64.getDecoder().decode(base64SealedKey));
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot unseal Flow session key", ex);
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
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot decrypt Flow payload", ex);
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
    try {
      String body = pem.replace("\\n", "\n").replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
      byte[] der = Base64.getDecoder().decode(body);
      return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (Exception ex) {
      throw new IllegalStateException("FLOW_PRIVATE_KEY_PEM is not a valid PKCS#8 RSA key", ex);
    }
  }
}
