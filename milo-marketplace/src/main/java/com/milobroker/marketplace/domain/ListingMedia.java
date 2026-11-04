package com.milobroker.marketplace.domain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
/** Media metadata row (V3 listing_media). Bytes live in private S3; this row stores the key. */
@Entity @Table(name = "listing_media")
public class ListingMedia {
  @Id @Column(name = "id", nullable = false)
  private UUID id;
  @Column(name = "listing_id", nullable = false)
  private UUID listingId;
  @Column(name = "object_key", nullable = false, unique = true, length = 512)
  private String objectKey;
  @Column(name = "media_type", nullable = false, length = 8)
  private String mediaType;
  @Column(name = "content_type", nullable = false, length = 128)
  private String contentType;
  @Column(name = "size_bytes", nullable = false)
  private Long sizeBytes;
  @Column(name = "sort_order", nullable = false)
  private Integer sortOrder = 0;
  @Column(name = "upload_status", nullable = false, length = 16)
  private String uploadStatus;
  @Column(name = "created_at", nullable = false)
  private Instant createdAt = Instant.now();
  public ListingMedia() {}
  @PrePersist
  void stamp() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    createdAt = Instant.now();
  }
  public UUID getId() { return id; }
  public UUID getListingId() { return listingId; }
  public void setListingId(UUID listingId) { this.listingId = listingId; }
  public String getObjectKey() { return objectKey; }
  public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
  public String getMediaType() { return mediaType; }
  public void setMediaType(String mediaType) { this.mediaType = mediaType; }
  public String getContentType() { return contentType; }
  public void setContentType(String contentType) { this.contentType = contentType; }
  public Long getSizeBytes() { return sizeBytes; }
  public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public String getUploadStatus() { return uploadStatus; }
  public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }
  public Instant getCreatedAt() { return createdAt; }
}
