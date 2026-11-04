package com.milobroker.marketplace.service;
import com.milobroker.marketplace.domain.ListingMedia;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.ListingMediaRepository;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
/**
 * Media metadata around the private bucket: presigned browser uploads,
 * confirmation flips, and presigned reads. Bytes never pass through the app.
 */
@Service @Transactional
public class MediaService {
  private static final Set<String> MEDIA_TYPES = Set.of("IMAGE", "VIDEO");
  private final PropertyListingRepository listings;
  private final ListingMediaRepository media;
  private final MediaUrlSigner signer;
  private final String bucket;
  private final Duration ttl;
  private final long maxImageBytes;
  private final long maxVideoBytes;
  public MediaService(PropertyListingRepository listings, ListingMediaRepository media,
      MediaUrlSigner signer,
      @Value("${milobroker.media.bucket:}") String bucket,
      @Value("${milobroker.media.presign-minutes:15}") long presignMinutes,
      @Value("${milobroker.media.max-image-bytes:10485760}") long maxImageBytes,
      @Value("${milobroker.media.max-video-bytes:104857600}") long maxVideoBytes) {
    this.listings = listings;
    this.media = media;
    this.signer = signer;
    this.bucket = bucket == null ? "" : bucket;
    this.ttl = Duration.ofMinutes(Math.max(1, presignMinutes));
    this.maxImageBytes = maxImageBytes;
    this.maxVideoBytes = maxVideoBytes;
  }
  public UploadUrlResponse uploadUrl(UUID listingId, UploadUrlRequest request) {
    requireConfigured();
    PropertyListing listing = findListing(listingId);
    if (!"DRAFT".equals(listing.getStatus()) && !"PENDING".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Media can only be added while the listing is being prepared");
    }
    String mediaType = mediaType(request.mediaType());
    String contentType = contentType(request.contentType(), mediaType);
    long size = size(request.sizeBytes(), mediaType);
    String key = "listings/" + listingId + "/" + UUID.randomUUID() + "." + extension(contentType);
    ListingMedia row = new ListingMedia();
    row.setListingId(listingId);
    row.setObjectKey(key);
    row.setMediaType(mediaType);
    row.setContentType(contentType);
    row.setSizeBytes(size);
    row.setSortOrder(media.countByListingId(listingId));
    row.setUploadStatus("PENDING");
    media.save(row);
    String url = signer.presignPut(bucket, key, contentType, size, ttl);
    return new UploadUrlResponse(key, url, Instant.now().plus(ttl));
  }
  public MediaRecord confirm(UUID listingId, String objectKey) {
    requireConfigured();
    findListing(listingId);
    ListingMedia row = media.findByListingIdAndObjectKey(listingId, objectKey)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Media not found"));
    if (!"PENDING".equals(row.getUploadStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only PENDING uploads can be confirmed");
    }
    row.setUploadStatus("READY");
    return record(media.save(row), true);
  }
  @Transactional(readOnly = true)
  public List<MediaRecord> list(UUID listingId) {
    requireConfigured();
    findListing(listingId);
    return media.findByListingIdOrderBySortOrderAsc(listingId).stream()
      .map(row -> record(row, "READY".equals(row.getUploadStatus()))).toList();
  }
  private PropertyListing findListing(UUID listingId) {
    return listings.findById(listingId).orElseThrow(() ->
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
  }
  private void requireConfigured() {
    if (bucket.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media bucket is not configured");
    }
  }
  private MediaRecord record(ListingMedia row, boolean withUrl) {
    String url = withUrl ? signer.presignGet(bucket, row.getObjectKey(), ttl) : null;
    return new MediaRecord(row.getId(), row.getObjectKey(), row.getMediaType(), row.getContentType(),
      row.getSizeBytes(), row.getSortOrder(), row.getUploadStatus(), url);
  }
  private static String mediaType(String value) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mediaType is required");
    }
    String type = value.trim().toUpperCase(Locale.ROOT);
    if (!MEDIA_TYPES.contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown mediaType: " + value);
    }
    return type;
  }
  private static String contentType(String value, String mediaType) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentType is required");
    }
    String type = value.trim();
    String prefix = mediaType.equals("IMAGE") ? "image/" : "video/";
    if (!type.toLowerCase(Locale.ROOT).startsWith(prefix)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "contentType must start with " + prefix);
    }
    return type;
  }
  private long size(Long value, String mediaType) {
    if (value == null || value <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sizeBytes must be positive");
    }
    long cap = mediaType.equals("IMAGE") ? maxImageBytes : maxVideoBytes;
    if (value > cap) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "sizeBytes exceeds the " + cap + " byte limit");
    }
    return value;
  }
  private static String extension(String contentType) {
    int slash = contentType.indexOf('/');
    String raw = slash < 0 ? "" : contentType.substring(slash + 1);
    String ext = raw.split("[^a-z0-9]", 2)[0].toLowerCase(Locale.ROOT);
    return ext.isEmpty() ? "bin" : ext;
  }
  public record UploadUrlRequest(String mediaType, String contentType, Long sizeBytes) {}
  public record ConfirmRequest(String objectKey) {}
  public record UploadUrlResponse(String objectKey, String uploadUrl, Instant expiresAt) {}
  public record MediaRecord(UUID id, String objectKey, String mediaType, String contentType,
      Long sizeBytes, Integer sortOrder, String uploadStatus, String url) {}
}
