package com.milobroker.marketplace.repository;
import com.milobroker.marketplace.domain.ListingMedia;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/** Media rows per listing, in display order. */
public interface ListingMediaRepository extends JpaRepository<ListingMedia, UUID> {
  List<ListingMedia> findByListingIdOrderBySortOrderAsc(UUID listingId);
  Optional<ListingMedia> findByListingIdAndObjectKey(UUID listingId, String objectKey);
  int countByListingId(UUID listingId);
}
