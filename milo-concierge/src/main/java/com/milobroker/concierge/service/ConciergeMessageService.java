package com.milobroker.concierge.service;
import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.domain.InboundEvent;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import com.milobroker.concierge.repository.InboundEventRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
/**
 * Persists inbound envelopes idempotently on the channel message id.
 * Routing to Marketplace/Atlas/model is the next step; this service only records.
 */
@Service
public class ConciergeMessageService {
  private final ConversationSessionRepository sessions;
  private final InboundEventRepository events;
  public ConciergeMessageService(ConversationSessionRepository sessions,
      InboundEventRepository events) {
    this.sessions = sessions;
    this.events = events;
  }
  @Transactional
  public AcceptedMessage accept(UUID sessionId, String channel, String channelUserId,
      String eventType, String payloadJson, String channelMessageId) {
    ConversationSession session = sessionId == null
      ? openSession(channel, channelUserId)
      : sessions.findById(sessionId).orElseThrow(() ->
        new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));
    Optional<InboundEvent> seen = events.findByChannelMessageId(channelMessageId);
    if (seen.isPresent()) {
      InboundEvent existing = seen.get();
      return new AcceptedMessage(existing.getId(), existing.getSessionId(), "DUPLICATE", Instant.now());
    }
    InboundEvent event = new InboundEvent();
    event.setSessionId(session.getId());
    event.setChannelMessageId(channelMessageId);
    event.setEventType(eventType);
    event.setPayload(payloadJson);
    event.setProcessingStatus("RECEIVED");
    try {
      event = events.save(event);
    } catch (DataIntegrityViolationException ex) {
      InboundEvent existing = events.findByChannelMessageId(channelMessageId).orElseThrow(() -> ex);
      return new AcceptedMessage(existing.getId(), existing.getSessionId(), "DUPLICATE", Instant.now());
    }
    return new AcceptedMessage(event.getId(), session.getId(), "RECEIVED", Instant.now());
  }
  private ConversationSession openSession(String channel, String channelUserId) {
    if (channelUserId == null || channelUserId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "channelUserId is required without sessionId");
    }
    ConversationSession session = new ConversationSession();
    session.setChannel(channel == null || channel.isBlank() ? "whatsapp" : channel);
    session.setChannelUserId(channelUserId);
    session.setState("OPEN");
    return sessions.save(session);
  }
  public record AcceptedMessage(UUID eventId, UUID sessionId, String status, Instant acceptedAt) {}
}
