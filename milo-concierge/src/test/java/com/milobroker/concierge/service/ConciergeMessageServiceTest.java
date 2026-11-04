package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.domain.InboundEvent;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import com.milobroker.concierge.repository.InboundEventRepository;
import com.milobroker.concierge.service.ConciergeMessageService.AcceptedMessage;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Session resolution and channel-message idempotency; no routing yet. */
@ExtendWith(MockitoExtension.class)
class ConciergeMessageServiceTest {
  @Mock ConversationSessionRepository sessions;
  @Mock InboundEventRepository events;
  private ConciergeMessageService service;

  @BeforeEach
  void setup() {
    service = new ConciergeMessageService(sessions, events);
  }

  @Test
  void newMessageOpensSessionAndRecordsEvent() {
    ConversationSession session = new ConversationSession();
    session.setId(UUID.randomUUID());
    when(sessions.save(any())).thenReturn(session);
    when(events.findByChannelMessageId("m-1")).thenReturn(Optional.empty());
    when(events.save(any())).thenAnswer(inv -> {
      InboundEvent event = inv.getArgument(0);
      return event;
    });
    AcceptedMessage accepted = service.accept(null, "whatsapp", "user-9", "TEXT",
      "{\"text\":\"hi\"}", "m-1");
    assertEquals("RECEIVED", accepted.status());
    assertEquals(session.getId(), accepted.sessionId());
  }

  @Test
  void duplicateChannelMessageIsAcknowledged() {
    InboundEvent existing = new InboundEvent();
    existing.setSessionId(UUID.randomUUID());
    when(sessions.findById(existing.getSessionId())).thenReturn(Optional.of(new ConversationSession()));
    when(events.findByChannelMessageId("m-1")).thenReturn(Optional.of(existing));
    AcceptedMessage accepted = service.accept(existing.getSessionId(), null, null, "TEXT",
      "{\"text\":\"hi\"}", "m-1");
    assertEquals("DUPLICATE", accepted.status());
  }

  @Test
  void unknownSessionIsNotFound() {
    when(sessions.findById(any())).thenReturn(Optional.empty());
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.accept(UUID.randomUUID(), null, null, "TEXT", "{}", "m-1"));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
  }

  @Test
  void missingUserWithoutSessionIsRejected() {
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.accept(null, null, " ", "TEXT", "{}", "m-1"));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }
}
