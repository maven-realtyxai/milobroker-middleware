package com.milobroker.marketplace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.milobroker.marketplace.domain.ListingMedia;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.ListingMediaRepository;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import com.milobroker.marketplace.service.MediaService.MediaRecord;
import com.milobroker.marketplace.service.MediaService.UploadUrlRequest;
import com.milobroker.marketplace.service.MediaService.UploadUrlResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Media metadata rules; the signer is mocked so no S3 is touched. */
@ExtendWith(MockitoExtension.class)
class MediaServiceTest {
  @Mock PropertyListingRepository listings;
  @Mock ListingMediaRepository media;
  @Mock MediaUrlSigner signer;
  private MediaService service;

  @BeforeEach
  void setup() {
    service = new MediaService(listings, media, signer, "test-bucket", 15, 10485760, 104857600);
  }

  private PropertyListing listing(String status) {
    PropertyListing row = new PropertyListing();
    row.setStatus(status);
    return row;
  }

  @Test
  void uploadUrlInsertsPendingRow() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("DRAFT")));
    when(media.countByListingId(any())).thenReturn(0);
    when(media.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(signer.presignPut(anyString(), anyString(), anyString(), anyLong(), any(Duration.class)))
      .thenReturn("https://s3/put");
    UploadUrlResponse response = service.uploadUrl(UUID.randomUUID(),
      new UploadUrlRequest("IMAGE", "image/jpeg", 1024L));
    assertEquals("https://s3/put", response.uploadUrl());
  }

  @Test
  void oversizeUploadIsRejected() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("DRAFT")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.uploadUrl(UUID.randomUUID(),
        new UploadUrlRequest("IMAGE", "image/jpeg", 999999999L)));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void mismatchedContentTypeIsRejected() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("DRAFT")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.uploadUrl(UUID.randomUUID(),
        new UploadUrlRequest("IMAGE", "video/mp4", 1024L)));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void missingBucketIsUnavailable() {
    MediaService unconfigured = new MediaService(listings, media, signer, "", 15, 1, 1);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> unconfigured.uploadUrl(UUID.randomUUID(),
        new UploadUrlRequest("IMAGE", "image/jpeg", 1024L)));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }

  @Test
  void confirmFlipsPendingToReady() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("DRAFT")));
    ListingMedia row = new ListingMedia();
    row.setListingId(UUID.randomUUID());
    row.setObjectKey("k");
    row.setUploadStatus("PENDING");
    when(media.findByListingIdAndObjectKey(any(), any())).thenReturn(Optional.of(row));
    when(media.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(signer.presignGet(anyString(), anyString(), any(Duration.class)))
      .thenReturn("https://s3/get");
    MediaRecord confirmed = service.confirm(UUID.randomUUID(), "k");
    assertEquals("READY", confirmed.uploadStatus());
  }

  @Test
  void listSkipsUrlsForPendingRows() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("DRAFT")));
    ListingMedia pending = new ListingMedia();
    pending.setUploadStatus("PENDING");
    when(media.findByListingIdOrderBySortOrderAsc(any())).thenReturn(List.of(pending));
    List<MediaRecord> rows = service.list(UUID.randomUUID());
    assertEquals(1, rows.size());
  }
}
