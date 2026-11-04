package com.milobroker.marketplace.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import com.milobroker.marketplace.service.UserDeletionService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserDeletionControllerTest {
  @Test void requiresConfiguredMatchingSecret() {
    var service = mock(UserDeletionService.class);
    var controller = new UserDeletionController(service, "correct");
    assertThrows(ResponseStatusException.class, () -> controller.delete("91123", null));
    assertThrows(ResponseStatusException.class, () -> controller.delete("91123", "wrong"));
    verifyNoInteractions(service);
    controller.delete("91123", "correct");
    verify(service).delete("91123");
  }
  @Test void emptySecretNeverAuthorizes() {
    var service = mock(UserDeletionService.class);
    assertThrows(ResponseStatusException.class, () -> new UserDeletionController(service, "").delete("91123", ""));
    verifyNoInteractions(service);
  }
}
