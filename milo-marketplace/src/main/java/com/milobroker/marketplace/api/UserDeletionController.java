package com.milobroker.marketplace.api;

import com.milobroker.marketplace.service.UserDeletionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Internal endpoint; the WhatsApp webhook verifies the sender and confirmation first. */
@RestController
@RequestMapping("/api/v1/onboarding/users")
public class UserDeletionController {
  private final UserDeletionService deletion;
  private final String secret;
  public UserDeletionController(UserDeletionService deletion,
      @Value("${milobroker.internal.deletion-secret:}") String secret) {
    this.deletion = deletion; this.secret = secret;
  }
  @DeleteMapping("/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String userId,
      @RequestHeader(value = "X-Internal-Deletion-Key", required = false) String key) {
    if (secret == null || secret.isBlank() || key == null || !MessageDigest.isEqual(
        secret.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
    deletion.delete(userId);
  }
}
