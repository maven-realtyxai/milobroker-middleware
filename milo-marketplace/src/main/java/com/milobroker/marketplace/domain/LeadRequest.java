package com.milobroker.marketplace.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
/** Lead request row (V5 lead_requests). Status transitions are guarded in the service. */
@Entity @Table(name = "lead_requests")
public class LeadRequest {
  @Id @Column(name = "id", nullable = false)
  private UUID id;
  @Column(name = "listing_id", nullable = false)
  private UUID listingId;
  @Column(name = "requester_channel_user_id", nullable = false)
  private String requesterChannelUserId;
  @Column(name = "message", columnDefinition = "TEXT")
  private String message;
  @Column(name = "status", nullable = false, length = 16)
  private String status;
  @Column(name = "consented_at")
  private Instant consentedAt;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();
  public LeadRequest() {}
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
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public UUID getListingId() { return listingId; }
  public void setListingId(UUID listingId) { this.listingId = listingId; }
  public String getRequesterChannelUserId() { return requesterChannelUserId; }
  public void setRequesterChannelUserId(String requesterChannelUserId) {
    this.requesterChannelUserId = requesterChannelUserId;
  }
  public String getMessage() { return message; }
  public void setMessage(String message) { this.message = message; }
  public Instant getConsentedAt() { return consentedAt; }
  public void setConsentedAt(Instant consentedAt) { this.consentedAt = consentedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
