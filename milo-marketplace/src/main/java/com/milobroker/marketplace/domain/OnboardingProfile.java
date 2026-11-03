package com.milobroker.marketplace.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
/** User record for one WhatsApp user. Owned by Marketplace; Concierge calls the API. */
@Entity @Table(name = "users")
public class OnboardingProfile {
  @Id @Column(name = "channel_user_id", nullable = false)
  private String channelUserId;
  @Column(name = "display_name")
  private String displayName;
  @Column(name = "intent", length = 32)
  private String intent;
  @Column(name = "state_code", length = 8)
  private String stateCode;
  @Column(name = "city_code")
  private String cityCode;
  @Column(name = "completed", nullable = false)
  private boolean completed;
  @Column(name = "welcome_sent", nullable = false)
  private boolean welcomeSent;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();
  protected OnboardingProfile() {}
  public OnboardingProfile(String channelUserId) {
    this.channelUserId = channelUserId;
  }
  @PreUpdate
  void touch() { this.updatedAt = Instant.now(); }
  public String getChannelUserId() { return channelUserId; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getIntent() { return intent; }
  public void setIntent(String intent) { this.intent = intent; }
  public String getStateCode() { return stateCode; }
  public void setStateCode(String stateCode) { this.stateCode = stateCode; }
  public String getCityCode() { return cityCode; }
  public void setCityCode(String cityCode) { this.cityCode = cityCode; }
  public boolean isCompleted() { return completed; }
  public void setCompleted(boolean completed) { this.completed = completed; }
  public boolean isWelcomeSent() { return welcomeSent; }
  public void setWelcomeSent(boolean welcomeSent) { this.welcomeSent = welcomeSent; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
