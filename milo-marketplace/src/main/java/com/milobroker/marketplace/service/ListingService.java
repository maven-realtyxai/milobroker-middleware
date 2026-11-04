package com.milobroker.marketplace.service;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
/**
 * Listing lifecycle (prototype auto-approve): create -> ACTIVE directly.
 * Approve/reject endpoints stay dormant but working for a future moderation pass;
 * REJECTED returns to DRAFT, anything except CLOSED can be closed.
 */
@Service @Transactional
public class ListingService {
  private static final Set<String> LISTING_TYPES = Set.of("RENT", "SALE");
  private static final Set<String> PROPERTY_TYPES =
    Set.of("APARTMENT", "HOUSE", "VILLA", "PG", "PLOT", "COMMERCIAL", "OTHER");
  private static final Set<String> FURNISHINGS =
    Set.of("FURNISHED", "SEMI_FURNISHED", "UNFURNISHED");
  private final PropertyListingRepository listings;
  public ListingService(PropertyListingRepository listings) {
    this.listings = listings;
  }
  public ListingRecord create(CreateListingRequest request) {
    if (listings.countByOwnerUserIdAndStatus(request.ownerUserId(), "ACTIVE") >= 5) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "You already have 5 active listings. Close one before posting a new property.");
    }
    PropertyListing listing = new PropertyListing();
    listing.setOwnerUserId(required(request.ownerUserId(), "ownerUserId"));
    listing.setTitle(required(request.title(), "title"));
    listing.setDescription(required(request.description(), "description"));
    listing.setListingType(listingType(request.listingType()));
    listing.setPropertyType(propertyType(request.propertyType()));
    listing.setCityCode(required(request.cityCode(), "cityCode"));
    listing.setLocalityCode(required(request.localityCode(), "localityCode"));
    listing.setBedrooms(range(request.bedrooms(), 0, 20, "bedrooms"));
    listing.setBathrooms(range(request.bathrooms(), 0, 20, "bathrooms"));
    listing.setFurnishing(request.furnishing() == null ? null : furnishing(request.furnishing()));
    listing.setPriceInr(positive(request.priceInr(), "priceInr"));
    listing.setAvailableFrom(request.availableFrom());
    // Prototype auto-approve: listings go live immediately; moderation stays dormant.
    listing.setStatus("ACTIVE");
    return record(listings.save(listing));
  }
  @Transactional(readOnly = true)
  public ListingRecord get(UUID id) {
    return record(find(id));
  }
  @Transactional(readOnly = true)
  public List<ListingRecord> listByOwner(String ownerUserId, String status) {
    if (ownerUserId == null || ownerUserId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ownerUserId is required");
    }
    List<PropertyListing> rows = status == null || status.isBlank()
      ? listings.findByOwnerUserIdOrderByCreatedAtDesc(ownerUserId)
      : listings.findByOwnerUserIdAndStatusOrderByCreatedAtDesc(ownerUserId, status.toUpperCase(Locale.ROOT));
    return rows.stream().map(ListingService::record).toList();
  }
  public ListingRecord update(UUID id, UpdateListingRequest request) {
    PropertyListing listing = find(id);
    if (!"DRAFT".equals(listing.getStatus()) && !"ACTIVE".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only DRAFT or ACTIVE listings can be edited");
    }
    if (request.title() != null) {
      listing.setTitle(required(request.title(), "title"));
    }
    if (request.description() != null) {
      listing.setDescription(required(request.description(), "description"));
    }
    if (request.listingType() != null) {
      listing.setListingType(listingType(request.listingType()));
    }
    if (request.propertyType() != null) {
      listing.setPropertyType(propertyType(request.propertyType()));
    }
    if (request.cityCode() != null) {
      listing.setCityCode(required(request.cityCode(), "cityCode"));
    }
    if (request.localityCode() != null) {
      listing.setLocalityCode(required(request.localityCode(), "localityCode"));
    }
    if (request.bedrooms() != null) {
      listing.setBedrooms(range(request.bedrooms(), 0, 20, "bedrooms"));
    }
    if (request.bathrooms() != null) {
      listing.setBathrooms(range(request.bathrooms(), 0, 20, "bathrooms"));
    }
    if (request.furnishing() != null) {
      listing.setFurnishing(furnishing(request.furnishing()));
    }
    if (request.priceInr() != null) {
      listing.setPriceInr(positive(request.priceInr(), "priceInr"));
    }
    if (request.availableFrom() != null) {
      listing.setAvailableFrom(request.availableFrom());
    }
    if ("submit".equalsIgnoreCase(request.action())) {
      listing.setStatus("PENDING");
      listing.setRejectionReason(null);
    } else if (request.action() != null && !"close".equalsIgnoreCase(request.action())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown action: " + request.action());
    }
    if ("close".equalsIgnoreCase(request.action())) {
      listing.setStatus("CLOSED");
    }
    return record(listings.save(listing));
  }
  public ListingRecord resubmit(UUID id) {
    PropertyListing listing = find(id);
    if (!"REJECTED".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only REJECTED listings can be resubmitted");
    }
    listing.setStatus("DRAFT");
    listing.setRejectionReason(null);
    return record(listings.save(listing));
  }
  public ListingRecord approve(UUID id) {
    PropertyListing listing = find(id);
    if (!"PENDING".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only PENDING listings can be approved");
    }
    listing.setStatus("ACTIVE");
    return record(listings.save(listing));
  }
  public ListingRecord reject(UUID id, String reason) {
    PropertyListing listing = find(id);
    if (!"PENDING".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only PENDING listings can be rejected");
    }
    if (reason == null || reason.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required");
    }
    listing.setStatus("REJECTED");
    listing.setRejectionReason(reason.trim());
    return record(listings.save(listing));
  }
  public ListingRecord close(UUID id) {
    PropertyListing listing = find(id);
    if ("CLOSED".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Listing is already closed");
    }
    listing.setStatus("CLOSED");
    return record(listings.save(listing));
  }
  private PropertyListing find(UUID id) {
    return listings.findById(id).orElseThrow(() ->
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
  }
  private static ListingRecord record(PropertyListing listing) {
    return new ListingRecord(listing.getId(), listing.getOwnerUserId(), listing.getTitle(),
      listing.getDescription(), listing.getListingType(), listing.getPropertyType(),
      listing.getCityCode(), listing.getLocalityCode(), listing.getBedrooms(), listing.getBathrooms(),
      listing.getFurnishing(), listing.getPriceInr(), listing.getAvailableFrom(), listing.getStatus(),
      listing.getRejectionReason(), listing.getCreatedAt(), listing.getUpdatedAt());
  }
  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value.trim();
  }
  private static String listingType(String value) {
    String code = required(value, "listingType").toUpperCase(Locale.ROOT);
    if (!LISTING_TYPES.contains(code)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown listingType: " + value);
    }
    return code;
  }
  private static String propertyType(String value) {
    String code = required(value, "propertyType").toUpperCase(Locale.ROOT);
    if (!PROPERTY_TYPES.contains(code)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown propertyType: " + value);
    }
    return code;
  }
  private static String furnishing(String value) {
    String code = value.trim().toUpperCase(Locale.ROOT);
    if (!FURNISHINGS.contains(code)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown furnishing: " + value);
    }
    return code;
  }
  private static Integer range(Integer value, int min, int max, String field) {
    if (value == null) {
      return null;
    }
    if (value < min || value > max) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        field + " must be between " + min + " and " + max);
    }
    return value;
  }
  private static Long positive(Long value, String field) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    if (value <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be positive");
    }
    return value;
  }
  public record CreateListingRequest(String ownerUserId, String title, String description,
      String listingType, String propertyType, String cityCode, String localityCode,
      Integer bedrooms, Integer bathrooms, String furnishing, Long priceInr, LocalDate availableFrom) {}
  public record UpdateListingRequest(String title, String description, String listingType,
      String propertyType, String cityCode, String localityCode, Integer bedrooms, Integer bathrooms,
      String furnishing, Long priceInr, LocalDate availableFrom, String action) {}
  public record RejectRequest(String reason) {}
  public record ListingRecord(UUID id, String ownerUserId, String title, String description,
      String listingType, String propertyType, String cityCode, String localityCode,
      Integer bedrooms, Integer bathrooms, String furnishing, Long priceInr, LocalDate availableFrom,
      String status, String rejectionReason, Instant createdAt, Instant updatedAt) {}
}
