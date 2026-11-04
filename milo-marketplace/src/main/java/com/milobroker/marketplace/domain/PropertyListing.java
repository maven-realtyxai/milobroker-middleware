package com.milobroker.marketplace.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
/** Property listing row (V3 property_listings). Status transitions are guarded in the service. */
@Entity @Table(name = "property_listings")
public class PropertyListing {
  @Id @Column(name = "id", nullable = false)
  private UUID id;
  @Column(name = "owner_user_id", nullable = false)
  private String ownerUserId;
  @Column(name = "title", nullable = false, length = 160)
  private String title;
  @Column(name = "description", nullable = false, columnDefinition = "TEXT")
  private String description;
  @Column(name = "listing_type", nullable = false, length = 16)
  private String listingType;
  @Column(name = "property_type", nullable = false, length = 24)
  private String propertyType;
  @Column(name = "city_code", nullable = false, length = 32)
  private String cityCode;
  @Column(name = "locality_code", nullable = false, length = 64)
  private String localityCode;
  @Column(name = "bedrooms")
  private Integer bedrooms;
  @Column(name = "bathrooms")
  private Integer bathrooms;
  @Column(name = "furnishing", length = 24)
  private String furnishing;
  @Column(name = "price_inr", nullable = false)
  private Long priceInr;
  @Column(name = "available_from")
  private LocalDate availableFrom;
  @Column(name = "status", nullable = false, length = 16)
  private String status;
  @Column(name = "rejection_reason", columnDefinition = "TEXT")
  private String rejectionReason;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();
  public PropertyListing() {}
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
  public String getOwnerUserId() { return ownerUserId; }
  public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getListingType() { return listingType; }
  public void setListingType(String listingType) { this.listingType = listingType; }
  public String getPropertyType() { return propertyType; }
  public void setPropertyType(String propertyType) { this.propertyType = propertyType; }
  public String getCityCode() { return cityCode; }
  public void setCityCode(String cityCode) { this.cityCode = cityCode; }
  public String getLocalityCode() { return localityCode; }
  public void setLocalityCode(String localityCode) { this.localityCode = localityCode; }
  public Integer getBedrooms() { return bedrooms; }
  public void setBedrooms(Integer bedrooms) { this.bedrooms = bedrooms; }
  public Integer getBathrooms() { return bathrooms; }
  public void setBathrooms(Integer bathrooms) { this.bathrooms = bathrooms; }
  public String getFurnishing() { return furnishing; }
  public void setFurnishing(String furnishing) { this.furnishing = furnishing; }
  public Long getPriceInr() { return priceInr; }
  public void setPriceInr(Long priceInr) { this.priceInr = priceInr; }
  public LocalDate getAvailableFrom() { return availableFrom; }
  public void setAvailableFrom(LocalDate availableFrom) { this.availableFrom = availableFrom; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getRejectionReason() { return rejectionReason; }
  public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
