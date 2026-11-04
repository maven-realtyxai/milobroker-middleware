package com.milobroker.marketplace.api;
import com.milobroker.marketplace.service.ListingService;
import com.milobroker.marketplace.service.ListingService.ListingRecord;
import com.milobroker.marketplace.service.ListingService.RejectRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
/** Moderation boundary. Internal only; the proxy blocks this subtree publicly. */
@RestController @RequestMapping("/api/v1/listings")
public class ModerationController {
  private final ListingService listings;
  public ModerationController(ListingService listings) {
    this.listings = listings;
  }
  @PostMapping("/{id}/approve")
  public ListingRecord approve(@PathVariable UUID id) {
    return listings.approve(id);
  }
  @PostMapping("/{id}/reject")
  public ListingRecord reject(@PathVariable UUID id,
      @Valid @RequestBody RejectPayload payload) {
    return listings.reject(id, new RejectRequest(payload.reason()).reason());
  }
  public record RejectPayload(@NotBlank String reason) {}
}
