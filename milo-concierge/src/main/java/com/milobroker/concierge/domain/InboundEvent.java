package com.milobroker.concierge.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
/** One inbound message envelope (V1 inbound_events). Idempotent on the channel message id. */
@Entity @Table(name = "inbound_events")
public class InboundEvent {
  @Id @Column(name = "id", nullable = false)
  private UUID id;
  @Column(name = "session_id", nullable = false)
  private UUID sessionId;
  @Column(name = "channel_message_id", nullable = false, unique = true, length = 255)
  private String channelMessageId;
  @Column(name = "event_type", nullable = false, length = 64)
  private String eventType;
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private String payload;
  @Column(name = "processing_status", nullable = false, length = 32)
  private String processingStatus;
  @Column(name = "received_at", nullable = false)
  private Instant receivedAt = Instant.now();
  @Column(name = "processed_at")
  private Instant processedAt;
  public InboundEvent() {}
  @PrePersist
  void stamp() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    receivedAt = Instant.now();
  }
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public UUID getSessionId() { return sessionId; }
  public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }
  public String getChannelMessageId() { return channelMessageId; }
  public void setChannelMessageId(String channelMessageId) { this.channelMessageId = channelMessageId; }
  public String getEventType() { return eventType; }
  public void setEventType(String eventType) { this.eventType = eventType; }
  public String getPayload() { return payload; }
  public void setPayload(String payload) { this.payload = payload; }
  public String getProcessingStatus() { return processingStatus; }
  public void setProcessingStatus(String processingStatus) { this.processingStatus = processingStatus; }
  public Instant getReceivedAt() { return receivedAt; }
  public Instant getProcessedAt() { return processedAt; }
  public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
