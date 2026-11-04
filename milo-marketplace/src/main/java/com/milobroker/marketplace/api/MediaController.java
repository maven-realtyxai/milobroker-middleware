package com.milobroker.marketplace.api;
import com.milobroker.marketplace.service.MediaService;
import com.milobroker.marketplace.service.MediaService.ConfirmRequest;
import com.milobroker.marketplace.service.MediaService.MediaRecord;
import com.milobroker.marketplace.service.MediaService.UploadUrlRequest;
import com.milobroker.marketplace.service.MediaService.UploadUrlResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
/** Media metadata boundary. Browsers move bytes directly with presigned S3 URLs. */
@RestController @RequestMapping("/api/v1/listings/{id}")
public class MediaController {
  private final MediaService media;
  public MediaController(MediaService media) {
    this.media = media;
  }
  @PostMapping("/media:upload-url")
  public UploadUrlResponse uploadUrl(@PathVariable UUID id,
      @Valid @RequestBody UploadUrlPayload payload) {
    return media.uploadUrl(id, new UploadUrlRequest(payload.mediaType(), payload.contentType(),
      payload.sizeBytes()));
  }
  @PostMapping("/media:confirm")
  public MediaRecord confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmPayload payload) {
    return media.confirm(id, new ConfirmRequest(payload.objectKey()).objectKey());
  }
  @GetMapping("/media")
  public List<MediaRecord> list(@PathVariable UUID id) {
    return media.list(id);
  }
  public record UploadUrlPayload(String mediaType, String contentType, Long sizeBytes) {}
  public record ConfirmPayload(String objectKey) {}
}
