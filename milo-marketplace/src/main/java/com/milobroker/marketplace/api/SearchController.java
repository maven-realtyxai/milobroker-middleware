package com.milobroker.marketplace.api;
import com.milobroker.marketplace.service.SearchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
/** Stable structured-search boundary used by Concierge. */
@RestController @RequestMapping("/api/v1/search")
public class SearchController {
  private final SearchService search;
  public SearchController(SearchService search) {
    this.search = search;
  }
  /** Searches ACTIVE listings; never exposes owner contact data. */
  @PostMapping public SearchResponse search(@Valid @RequestBody SearchRequest request) {
    List<SearchResult> results = search.search(request);
    return new SearchResponse(request, results, results.size());
  }
  /** Marketplace accepts validated fields, never free-form model output. */
  public record SearchRequest(@NotBlank String cityCode, @NotBlank String localityCode, @NotBlank String listingType, String propertyType, @Min(0) @Max(20) Integer bedrooms, @Positive Long maximumBudgetInr) {}
  /** Response shared with conversation and future web clients. */
  public record SearchResponse(@NotNull SearchRequest appliedFilters, @NotNull List<SearchResult> results, @Min(0) int total) {}
  /** Public projection deliberately excluding owner contact data. */
  public record SearchResult(String listingId, String title, long priceInr, String localityCode, String status, Integer bedrooms) {}
}
