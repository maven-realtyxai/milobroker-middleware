package com.milobroker.marketplace.api;
import com.milobroker.marketplace.service.ListingService;
import com.milobroker.marketplace.service.ListingService.CreateListingRequest;
import com.milobroker.marketplace.service.ListingService.ListingRecord;
import com.milobroker.marketplace.service.ListingService.UpdateListingRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
/** Listing write and read boundary. Writes stay internal; search serves the public query. */
@RestController @RequestMapping("/api/v1/listings")
public class ListingController {
  private final ListingService listings;
  public ListingController(ListingService listings) {
    this.listings = listings;
  }
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ListingRecord create(@Valid @RequestBody CreateListingPayload payload) {
    return listings.create(new CreateListingRequest(payload.ownerUserId(), payload.title(),
      payload.description(), payload.listingType(), payload.propertyType(), payload.cityCode(),
      payload.localityCode(), payload.bedrooms(), payload.bathrooms(), payload.furnishing(),
      payload.priceInr(), payload.availableFrom()));
  }
  @GetMapping("/{id}")
  public ListingRecord get(@PathVariable UUID id) {
    return listings.get(id);
  }
  @GetMapping
  public List<ListingRecord> listByOwner(@RequestParam String ownerUserId,
      @RequestParam(required = false) String status) {
    return listings.listByOwner(ownerUserId, status);
  }
  @PatchMapping("/{id}")
  public ListingRecord update(@PathVariable UUID id,
      @Valid @RequestBody UpdateListingPayload payload) {
    return listings.update(id, new UpdateListingRequest(payload.title(), payload.description(),
      payload.listingType(), payload.propertyType(), payload.cityCode(), payload.localityCode(),
      payload.bedrooms(), payload.bathrooms(), payload.furnishing(), payload.priceInr(),
      payload.availableFrom(), payload.action()));
  }
  @PostMapping("/{id}/resubmit")
  public ListingRecord resubmit(@PathVariable UUID id) {
    return listings.resubmit(id);
  }
  @PostMapping("/{id}/close")
  public ListingRecord close(@PathVariable UUID id) {
    return listings.close(id);
  }
  public record CreateListingPayload(String ownerUserId, String title, String description,
      String listingType, String propertyType, String cityCode, String localityCode,
      Integer bedrooms, Integer bathrooms, String furnishing, Long priceInr,
      java.time.LocalDate availableFrom) {}
  public record UpdateListingPayload(String title, String description, String listingType,
      String propertyType, String cityCode, String localityCode, Integer bedrooms, Integer bathrooms,
      String furnishing, Long priceInr, java.time.LocalDate availableFrom, String action) {}
}
