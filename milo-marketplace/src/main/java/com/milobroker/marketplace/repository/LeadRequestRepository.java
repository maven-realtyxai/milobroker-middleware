package com.milobroker.marketplace.repository;
import com.milobroker.marketplace.domain.LeadRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/** Leads by listing and by requester, newest first. */
public interface LeadRequestRepository extends JpaRepository<LeadRequest, UUID> {
  List<LeadRequest> findByListingIdOrderByCreatedAtDesc(UUID listingId);
  List<LeadRequest> findByRequesterChannelUserIdOrderByCreatedAtDesc(String requesterChannelUserId);
  List<LeadRequest> findByListingIdAndRequesterChannelUserIdOrderByCreatedAtDesc(
    UUID listingId, String requesterChannelUserId);
  Optional<LeadRequest> findByListingIdAndRequesterChannelUserIdAndStatus(
    UUID listingId, String requesterChannelUserId, String status);
}
