package com.milobroker.marketplace.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.milobroker.marketplace.api.SearchController.SearchRequest;
import com.milobroker.marketplace.api.SearchController.SearchResult;
import com.milobroker.marketplace.domain.PropertyListing;
import com.milobroker.marketplace.repository.PropertyListingRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/** Search maps ACTIVE rows to public projections without owner data. */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {
  @Mock PropertyListingRepository listings;
  private SearchService service;

  @BeforeEach
  void setup() {
    service = new SearchService(listings);
  }

  @Test
  void activeRowsMapToPublicProjection() {
    PropertyListing row = new PropertyListing();
    row.setTitle("2BHK");
    row.setPriceInr(25000L);
    row.setLocalityCode("BLR");
    row.setStatus("ACTIVE");
    org.springframework.test.util.ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
    when(listings.searchActive(any(), any(), any(), isNull(), isNull(), isNull(), any(Pageable.class)))
      .thenReturn(new PageImpl<>(List.of(row)));
    List<SearchResult> results = service.search(
      new SearchRequest("KA", "BLR", "RENT", null, null, null));
    assertEquals(1, results.size());
    assertEquals("2BHK", results.get(0).title());
    assertEquals("BLR", results.get(0).localityCode());
  }

  @Test
  void bedroomsFlowThroughProjection() {
    PropertyListing row = new PropertyListing();
    row.setTitle("3BHK");
    row.setPriceInr(45000L);
    row.setLocalityCode("BLR");
    row.setStatus("ACTIVE");
    row.setBedrooms(3);
    org.springframework.test.util.ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
    when(listings.searchActive(any(), any(), any(), isNull(), isNull(), isNull(), any(Pageable.class)))
      .thenReturn(new PageImpl<>(List.of(row)));
    List<SearchResult> results = service.search(
      new SearchRequest("KA", "BLR", "RENT", null, null, null));
    assertEquals(1, results.size());
    assertEquals(3, results.get(0).bedrooms());
  }
}
