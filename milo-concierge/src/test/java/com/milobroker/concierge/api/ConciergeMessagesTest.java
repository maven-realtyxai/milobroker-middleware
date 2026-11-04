package com.milobroker.concierge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.service.ConciergeMessageService;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.ConciergeMessageService.AcceptedMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** /messages validation and status mapping. The service is mocked; no database needed. */
@ExtendWith(MockitoExtension.class)
class ConciergeMessagesTest {
  @Mock ConciergeMessageService messages;
  private ConciergeController controller;

  @BeforeEach
  void setup() {
    controller = new ConciergeController(messages, new InputGuardrailService());
  }

  @Test
  void validMessageIsAccepted() {
    UUID eventId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(messages.accept(any(), any(), any(), any(), any(), any())).thenReturn(
      new AcceptedMessage(eventId, sessionId, "RECEIVED", Instant.now()));
    var response = controller.accept(
      "{\"text\":\"hi\",\"channelMessageId\":\"m-1\",\"channelUserId\":\"user-9\"}");
    assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
  }

  @Test
  void duplicateMessageIsAcknowledged() {
    when(messages.accept(any(), any(), any(), any(), any(), any())).thenReturn(
      new AcceptedMessage(UUID.randomUUID(), UUID.randomUUID(), "DUPLICATE", Instant.now()));
    var response = controller.accept(
      "{\"text\":\"hi\",\"channelMessageId\":\"m-1\",\"sessionId\":\"" + UUID.randomUUID() + "\"}");
    assertEquals(HttpStatus.OK, response.getStatusCode());
  }

  @Test
  void blankTextIsRejected() {
    assertThrows(ResponseStatusException.class,
      () -> controller.accept("{\"text\":\" \",\"channelMessageId\":\"m-1\"}"));
  }

  @Test
  void unknownServiceErrorPropagates() {
    when(messages.accept(any(), any(), any(), any(), any(), any())).thenThrow(
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> controller.accept("{\"text\":\"hi\",\"channelMessageId\":\"m-1\",\"sessionId\":\""
        + UUID.randomUUID() + "\"}"));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
  }
}
