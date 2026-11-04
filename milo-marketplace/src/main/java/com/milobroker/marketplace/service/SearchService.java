package com.milobroker.marketplace.service;
import com.milobroker.marketplace.api.SearchController.SearchRequest;
import com.milobroker.marketplace.api.SearchController.SearchResult;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/** Public search over ACTIVE listings only. Never exposes owner contact data. */
@Service @Transactional(readOnly = true)
public class SearchService {
  private static final int MAX_RESULTS = 50;
  private final PropertyListingRepository listings;
  public SearchService(PropertyListingRepository listings) {
    this.listings = listings;
  }
  public List<SearchResult> search(SearchRequest request) {
    List<PropertyListing> rows = listings.searchActive(
      request.cityCode(), request.localityCode(), request.listingType(),
      request.propertyType(), request.bedrooms(), request.maximumBudgetInr(),
      PageRequest.of(0, MAX_RESULTS)).getContent();
    return rows.stream().map(row -> new SearchResult(row.getId().toString(), row.getTitle(),
      row.getPriceInr(), row.getLocalityCode(), row.getStatus(), row.getBedrooms())).toList();
  }
}
