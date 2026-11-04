package com.milobroker.marketplace.repository;
import com.milobroker.marketplace.domain.PropertyListing;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
/** Listings by owner and public search over ACTIVE rows only. */
public interface PropertyListingRepository extends JpaRepository<PropertyListing, UUID> {
  List<PropertyListing> findByOwnerUserIdOrderByCreatedAtDesc(String ownerUserId);
  List<PropertyListing> findByOwnerUserIdAndStatusOrderByCreatedAtDesc(String ownerUserId, String status);
  long countByOwnerUserIdAndStatus(String ownerUserId, String status);
  @Query("select l from PropertyListing l where l.status = 'ACTIVE'"
    + " and l.cityCode = :city and l.localityCode = :locality and l.listingType = :type"
    + " and (:propertyType is null or l.propertyType = :propertyType)"
    + " and (:bedrooms is null or l.bedrooms >= :bedrooms)"
    + " and (:maxPrice is null or l.priceInr <= :maxPrice)")
  Page<PropertyListing> searchActive(@Param("city") String cityCode,
    @Param("locality") String localityCode, @Param("type") String listingType,
    @Param("propertyType") String propertyType, @Param("bedrooms") Integer bedrooms,
    @Param("maxPrice") Long maxPriceInr, Pageable pageable);
}
