package com.milobroker.concierge.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnTransformer;
import java.time.Instant;
import java.util.UUID;
/** One conversation thread (V1 conversation_sessions). Context stays in the database default. */
@Entity @Table(name = "conversation_sessions")
public class ConversationSession {
  @Id @Column(name = "id", nullable = false)
  private UUID id;
  @Column(name = "channel", nullable = false, length = 32)
  private String channel;
  @Column(name = "channel_user_id", nullable = false, length = 255)
  private String channelUserId;
  @Column(name = "state", nullable = false, length = 64)
  private String state;
  @ColumnTransformer(write = "?::jsonb")
  @Column(name = "context", nullable = false, columnDefinition = "jsonb")
  private String context = "{}";
  @Version @Column(name = "version", nullable = false)
  private long version;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();
  public ConversationSession() {}
  @PrePersist
  void stamp() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    createdAt = Instant.now();
    updatedAt = Instant.now();
  }
  @PreUpdate
  void touch() { this.updatedAt = Instant.now(); }
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getChannelUserId() { return channelUserId; }
  public void setChannelUserId(String channelUserId) { this.channelUserId = channelUserId; }
  public String getState() { return state; }
  public void setState(String state) { this.state = state; }
  public String getContext() { return context; }
  public void setContext(String context) { this.context = context; }
  public long getVersion() { return version; }
  public void setVersion(long version) { this.version = version; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
