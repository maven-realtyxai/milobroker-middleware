package com.milobroker.marketplace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.milobroker.marketplace.domain.LeadRequest;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.LeadRequestRepository;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import com.milobroker.marketplace.service.LeadService.ActorLeadRequest;
import com.milobroker.marketplace.service.LeadService.ConsentLeadRequest;
import com.milobroker.marketplace.service.LeadService.LeadRecord;
import com.milobroker.marketplace.service.LeadService.RequestLeadRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Consent guards: REQUESTED -> CONSENTED | DECLINED -> CLOSED. */
@ExtendWith(MockitoExtension.class)
class LeadServiceTest {
  @Mock LeadRequestRepository leads;
  @Mock PropertyListingRepository listings;
  private LeadService service;

  @BeforeEach
  void setup() {
    service = new LeadService(leads, listings);
  }

  private PropertyListing listing(String status, String owner) {
    PropertyListing row = new PropertyListing();
    row.setOwnerUserId(owner);
    row.setStatus(status);
    return row;
  }

  private LeadRequest lead(String status, String requester) {
    LeadRequest row = new LeadRequest();
    row.setListingId(UUID.randomUUID());
    row.setRequesterChannelUserId(requester);
    row.setStatus(status);
    return row;
  }

  @Test
  void requestOnActiveListingCreatesRequested() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("ACTIVE", "owner-1")));
    when(leads.findByListingIdAndRequesterChannelUserIdAndStatus(any(), any(), any()))
      .thenReturn(Optional.empty());
    when(leads.save(any())).thenAnswer(inv -> inv.getArgument(0));
    LeadRecord created = service.request(
      new RequestLeadRequest(UUID.randomUUID(), "buyer-1", "Interested"));
    assertEquals("REQUESTED", created.status());
  }

  @Test
  void duplicateOpenRequestIsIdempotent() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("ACTIVE", "owner-1")));
    when(leads.findByListingIdAndRequesterChannelUserIdAndStatus(any(), any(), any()))
      .thenReturn(Optional.of(lead("REQUESTED", "buyer-1")));
    LeadRecord existing = service.request(
      new RequestLeadRequest(UUID.randomUUID(), "buyer-1", "Again"));
    assertEquals("REQUESTED", existing.status());
  }

  @Test
  void requestOnMissingListingIsNotFound() {
    when(listings.findById(any())).thenReturn(Optional.empty());
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.request(new RequestLeadRequest(UUID.randomUUID(), "buyer-1", null)));
    assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
  }

  @Test
  void requestOnInactiveListingIsRejected() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("PENDING", "owner-1")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.request(new RequestLeadRequest(UUID.randomUUID(), "buyer-1", null)));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void ownerCannotRequestOwnListing() {
    when(listings.findById(any())).thenReturn(Optional.of(listing("ACTIVE", "owner-1")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.request(new RequestLeadRequest(UUID.randomUUID(), "owner-1", null)));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void consentWithGrantedFlagConsents() {
    when(leads.findById(any())).thenReturn(Optional.of(lead("REQUESTED", "buyer-1")));
    when(leads.save(any())).thenAnswer(inv -> inv.getArgument(0));
    LeadRecord consented = service.consent(UUID.randomUUID(),
      new ConsentLeadRequest("buyer-1", true));
    assertEquals("CONSENTED", consented.status());
    assertNotNull(consented.consentedAt());
  }

  @Test
  void consentWithoutGrantedFlagIsRejected() {
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.consent(UUID.randomUUID(), new ConsentLeadRequest("buyer-1", false)));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void consentByAnotherUserIsRejected() {
    when(leads.findById(any())).thenReturn(Optional.of(lead("REQUESTED", "buyer-1")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.consent(UUID.randomUUID(), new ConsentLeadRequest("buyer-2", true)));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void consentTwiceIsRejected() {
    when(leads.findById(any())).thenReturn(Optional.of(lead("CONSENTED", "buyer-1")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.consent(UUID.randomUUID(), new ConsentLeadRequest("buyer-1", true)));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }

  @Test
  void declineMovesRequestedToDeclined() {
    when(leads.findById(any())).thenReturn(Optional.of(lead("REQUESTED", "buyer-1")));
    when(leads.save(any())).thenAnswer(inv -> inv.getArgument(0));
    assertEquals("DECLINED",
      service.decline(UUID.randomUUID(), new ActorLeadRequest("buyer-1")).status());
  }

  @Test
  void closeIsTerminal() {
    when(leads.findById(any())).thenReturn(Optional.of(lead("CONSENTED", "buyer-1")));
    when(leads.save(any())).thenAnswer(inv -> inv.getArgument(0));
    assertEquals("CLOSED", service.close(UUID.randomUUID()).status());
    when(leads.findById(any())).thenReturn(Optional.of(lead("CLOSED", "buyer-1")));
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> service.close(UUID.randomUUID()));
    assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
  }
}
