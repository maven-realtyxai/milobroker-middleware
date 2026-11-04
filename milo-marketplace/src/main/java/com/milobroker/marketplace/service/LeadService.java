package com.milobroker.marketplace.service;
import com.milobroker.marketplace.domain.LeadRequest;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.LeadRequestRepository;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
/**
 * Consent-controlled lead lifecycle: REQUESTED -> CONSENTED | DECLINED -> CLOSED.
 * Only the requester consents or declines; close is terminal from any open state.
 */
@Service @Transactional
public class LeadService {
  private final LeadRequestRepository leads;
  private final PropertyListingRepository listings;
  public LeadService(LeadRequestRepository leads, PropertyListingRepository listings) {
    this.leads = leads;
    this.listings = listings;
  }
  public LeadRecord request(RequestLeadRequest request) {
    UUID listingId = requiredId(request.listingId(), "listingId");
    String requester = required(request.requesterChannelUserId(), "requesterChannelUserId");
    PropertyListing listing = listings.findById(listingId).orElseThrow(() ->
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
    if (!"ACTIVE".equals(listing.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Leads can only be requested on ACTIVE listings");
    }
    if (requester.equals(listing.getOwnerUserId())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Owner cannot request a lead on their own listing");
    }
    return leads.findByListingIdAndRequesterChannelUserIdAndStatus(
      listingId, requester, "REQUESTED").map(LeadService::record).orElseGet(() -> {
        LeadRequest lead = new LeadRequest();
        lead.setListingId(listingId);
        lead.setRequesterChannelUserId(requester);
        lead.setMessage(blankToNull(request.message()));
        lead.setStatus("REQUESTED");
        return record(leads.save(lead));
      });
  }
  public LeadRecord consent(UUID id, ConsentLeadRequest request) {
    String requester = required(request.requesterChannelUserId(), "requesterChannelUserId");
    if (request.granted() == null || !request.granted()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Explicit consent (granted=true) is required");
    }
    LeadRequest lead = find(id);
    if (!requester.equals(lead.getRequesterChannelUserId())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only the requester can consent to this lead");
    }
    if (!"REQUESTED".equals(lead.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only REQUESTED leads can be consented");
    }
    lead.setStatus("CONSENTED");
    lead.setConsentedAt(Instant.now());
    return record(leads.save(lead));
  }
  public LeadRecord decline(UUID id, ActorLeadRequest request) {
    String requester = required(request.requesterChannelUserId(), "requesterChannelUserId");
    LeadRequest lead = find(id);
    if (!requester.equals(lead.getRequesterChannelUserId())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only the requester can decline this lead");
    }
    if (!"REQUESTED".equals(lead.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
        "Only REQUESTED leads can be declined");
    }
    lead.setStatus("DECLINED");
    return record(leads.save(lead));
  }
  public LeadRecord close(UUID id) {
    LeadRequest lead = find(id);
    if ("CLOSED".equals(lead.getStatus())) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Lead is already closed");
    }
    lead.setStatus("CLOSED");
    return record(leads.save(lead));
  }
  @Transactional(readOnly = true)
  public LeadRecord get(UUID id) {
    return record(find(id));
  }
  @Transactional(readOnly = true)
  public List<LeadRecord> list(String requesterChannelUserId, UUID listingId) {
    boolean hasRequester = requesterChannelUserId != null && !requesterChannelUserId.isBlank();
    if (!hasRequester && listingId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "requester or listingId is required");
    }
    if (hasRequester && listingId != null) {
      return leads.findByListingIdAndRequesterChannelUserIdOrderByCreatedAtDesc(
        listingId, requesterChannelUserId.trim()).stream().map(LeadService::record).toList();
    }
    if (hasRequester) {
      return leads.findByRequesterChannelUserIdOrderByCreatedAtDesc(
        requesterChannelUserId.trim()).stream().map(LeadService::record).toList();
    }
    return leads.findByListingIdOrderByCreatedAtDesc(listingId)
      .stream().map(LeadService::record).toList();
  }
  private LeadRequest find(UUID id) {
    return leads.findById(id).orElseThrow(() ->
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
  }
  private static LeadRecord record(LeadRequest lead) {
    return new LeadRecord(lead.getId(), lead.getListingId(), lead.getRequesterChannelUserId(),
      lead.getMessage(), lead.getStatus(), lead.getConsentedAt(),
      lead.getCreatedAt(), lead.getUpdatedAt());
  }
  private static String required(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value.trim();
  }
  private static UUID requiredId(UUID value, String field) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
    }
    return value;
  }
  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
  public record RequestLeadRequest(UUID listingId, String requesterChannelUserId, String message) {}
  public record ConsentLeadRequest(String requesterChannelUserId, Boolean granted) {}
  public record ActorLeadRequest(String requesterChannelUserId) {}
  public record LeadRecord(UUID id, UUID listingId, String requesterChannelUserId, String message,
      String status, Instant consentedAt, Instant createdAt, Instant updatedAt) {}
}
