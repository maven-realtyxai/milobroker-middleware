package com.milobroker.marketplace.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** Removes Marketplace data only after its S3 objects have been removed. */
@Service
public class UserDeletionService {
  private final JdbcTemplate db;
  private final String bucket;
  private final String region;
  private final String endpoint;
  private final long presignMinutes;

  public UserDeletionService(JdbcTemplate db,
      @Value("${milobroker.media.bucket:}") String bucket,
      @Value("${milobroker.media.region:ap-southeast-2}") String region,
      @Value("${milobroker.media.endpoint:}") String endpoint,
      @Value("${milobroker.media.presign-minutes:15}") long presignMinutes) {
    this.db = db; this.bucket = bucket; this.region = region; this.endpoint = endpoint;
    this.presignMinutes = Math.max(1, presignMinutes);
  }

  @Transactional
  public void delete(String userId) {
    db.queryForList("SELECT channel_user_id FROM users WHERE channel_user_id = ? FOR UPDATE", userId);
    db.queryForList("SELECT id FROM property_listings WHERE owner_user_id = ? FOR UPDATE", userId);
    Integer recent = db.queryForObject("SELECT count(*) FROM listing_media m JOIN property_listings l "
      + "ON m.listing_id = l.id WHERE l.owner_user_id = ? AND m.created_at > now() - (? * interval '1 minute')",
      Integer.class, userId, presignMinutes);
    if (recent != null && recent > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Recent upload links must expire before deletion; try again later");
    }
    List<String> keys = db.queryForList("SELECT m.object_key FROM listing_media m JOIN property_listings l "
      + "ON m.listing_id = l.id WHERE l.owner_user_id = ?", String.class, userId);
    if (!keys.isEmpty()) {
      if (bucket == null || bucket.isBlank()) {
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media bucket is not configured");
      }
      deleteObjects(keys);
    }
    db.update("DELETE FROM lead_requests WHERE requester_channel_user_id = ? OR listing_id IN "
      + "(SELECT id FROM property_listings WHERE owner_user_id = ?)", userId, userId);
    db.update("DELETE FROM listing_media WHERE listing_id IN (SELECT id FROM property_listings WHERE owner_user_id = ?)", userId);
    db.update("DELETE FROM property_listings WHERE owner_user_id = ?", userId);
    db.update("DELETE FROM users WHERE channel_user_id = ?", userId);
  }

  private void deleteObjects(List<String> keys) {
    var builder = S3Client.builder().region(Region.of(region))
      .credentialsProvider(DefaultCredentialsProvider.create());
    if (endpoint != null && !endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
    try (S3Client s3 = builder.build()) {
      for (String key : keys) {
        // Deleting every version and delete marker handles both versioned and unversioned buckets.
        var pages = s3.listObjectVersionsPaginator(r -> r.bucket(bucket).prefix(key));
        List<String> versionIds = new ArrayList<>();
        for (var page : pages) {
          for (var version : page.versions()) {
            if (key.equals(version.key())) versionIds.add(version.versionId());
          }
          for (var marker : page.deleteMarkers()) {
            if (key.equals(marker.key())) versionIds.add(marker.versionId());
          }
        }
        for (String versionId : versionIds) s3.deleteObject(r -> r.bucket(bucket).key(key).versionId(versionId));
        s3.deleteObject(r -> r.bucket(bucket).key(key));
      }
    }
  }
}
