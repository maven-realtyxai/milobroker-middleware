package com.milobroker.marketplace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import com.milobroker.marketplace.service.ListingService.CreateListingRequest;
import com.milobroker.marketplace.service.ListingService.ListingRecord;
import com.milobroker.marketplace.service.ListingService.UpdateListingRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Status-transition guards: DRAFT -> PENDING -> ACTIVE|REJECTED -> CLOSED. */
@ExtendWith(MockitoExtension.class)
class ListingServiceTest {
  @Mock PropertyListingRepository listings;
  private ListingService service;

  @BeforeEach
  void setup() {
    service = new ListingService(listings);
  }

  private PropertyListing row(String status) {
    PropertyListing listing = new PropertyListing();
    listing.setOwnerUserId("user-1");
    listing.setTitle("2BHK");
    listing.setDescription("Near park");
    listing.setListingType("RENT");
    listing.setPropertyType("APARTMENT");
    listing.setCityCode("KA");
    listing.setLocalityCode("BLR");
    listing.setPriceInr(25000L);
    listing.setStatus(status);
    return listing;
  }

  private CreateListingRequest validCreate() {
    return new CreateListingRequest("user-1", "2BHK", "Near park", "RENT", "APARTMENT",
      "KA", "BLR", 2, 2, "SEMI_FURNISHED", 25000L, null);
  }

  @Test
  void createStartsActive() {
    when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ListingRecord created = service.create(validCreate());
    assertEquals("ACTIVE", created.status());
  }

  @Test
  void fifthActiveListingIsAllowed() {
    when(listings.countByOwnerUserIdAndStatus("user-1", "ACTIVE")).thenReturn(4L);
    when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ListingRecord created = service.create(validCreate());
    assertEquals("ACTIVE", created.status());
  }

  @Test
  void sixthActiveListingIsRejected() {
    when(listings.countByOwnerUserIdAndStatus("user-1", "ACTIVE")).thenReturn(5L);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.create(validCreate()));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
    assertEquals("You already have 5 active listings. Close one before posting a new property.",
      ex.getReason());
    verify(listings, never()).save(any());
  }

  @Test
  void unknownListingTypeIsRejected() {
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.create(new CreateListingRequest("user-1", "2BHK", "Near park", "LEASE",
        "APARTMENT", "KA", "BLR", 2, 2, null, 25000L, null)));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void onlyDraftCanBeEdited() {
    when(listings.findById(any())).thenReturn(Optional.of(row("PENDING")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.update(UUID.randomUUID(), new UpdateListingRequest("New", null, null,
        null, null, null, null, null, null, null, null, null)));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void approveMovesPendingToActive() {
    when(listings.findById(any())).thenReturn(Optional.of(row("PENDING")));
    when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    assertEquals("ACTIVE", service.approve(UUID.randomUUID()).status());
  }

  @Test
  void activeListingCanBeEdited() {
    PropertyListing row = new PropertyListing();
    row.setStatus("ACTIVE");
    when(listings.findById(any())).thenReturn(Optional.of(row));
    when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ListingRecord updated = service.update(UUID.randomUUID(),
      new UpdateListingRequest("New title", null, null, null, null, null, null, null, null,
        null, null, null));
    assertEquals("New title", updated.title());
    assertEquals("ACTIVE", updated.status());
  }

  @Test
  void approveDraftIsRejected() {
    when(listings.findById(any())).thenReturn(Optional.of(row("DRAFT")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.approve(UUID.randomUUID()));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void rejectRequiresReasonAndStoresIt() {
    when(listings.findById(any())).thenAnswer(inv -> Optional.of(row("PENDING")));
    when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    ListingRecord rejected = service.reject(UUID.randomUUID(), "Blurry photos");
    assertEquals("REJECTED", rejected.status());
    assertEquals("Blurry photos", rejected.rejectionReason());
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.reject(UUID.randomUUID(), " "));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void closeIsTerminal() {
    when(listings.findById(any())).thenReturn(Optional.of(row("ACTIVE")));
    when(listings.save(any())).thenAnswer(inv -> inv.getArgument(0));
    assertEquals("CLOSED", service.close(UUID.randomUUID()).status());
  }
}
