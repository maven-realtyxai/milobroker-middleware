package com.milobroker.marketplace.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.milobroker.marketplace.service.ListingService;
import com.milobroker.marketplace.service.ListingService.ListingRecord;
import com.milobroker.marketplace.service.MediaService;
import com.milobroker.marketplace.service.MediaService.UploadUrlResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/** HTTP contracts for listings, moderation, and media. Services mocked; no database required. */
@WebMvcTest({ListingController.class, ModerationController.class, MediaController.class})
class ListingWebTest {
  @Autowired MockMvc mvc;
  @MockitoBean ListingService listings;
  @MockitoBean MediaService media;

  private ListingRecord draft() {
    return new ListingRecord(UUID.randomUUID(), "user-1", "2BHK in BLR", "Near park", "RENT",
      "APARTMENT", "KA", "BLR", 2, 2, "SEMI_FURNISHED", 25000L, null, "DRAFT", null,
      Instant.now(), Instant.now());
  }

  private ListingRecord active() {
    return new ListingRecord(UUID.randomUUID(), "user-1", "2BHK in BLR", "Near park", "RENT",
      "APARTMENT", "KA", "BLR", 2, 2, "SEMI_FURNISHED", 25000L, null, "ACTIVE", null,
      Instant.now(), Instant.now());
  }

  @Test
  void createReturnsActive() throws Exception {
    when(listings.create(any())).thenReturn(active());
    mvc.perform(post("/api/v1/listings").contentType(MediaType.APPLICATION_JSON).content(
      "{\"ownerUserId\":\"user-1\",\"title\":\"2BHK in BLR\",\"description\":\"Near park\","
        + "\"listingType\":\"RENT\",\"propertyType\":\"APARTMENT\",\"cityCode\":\"KA\","
        + "\"localityCode\":\"BLR\",\"bedrooms\":2,\"priceInr\":25000}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void unknownListingIsNotFound() throws Exception {
    when(listings.get(any())).thenThrow(
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Listing not found"));
    mvc.perform(get("/api/v1/listings/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void invalidTransitionIsUnprocessable() throws Exception {
    when(listings.approve(any())).thenThrow(
      new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Only PENDING listings can be approved"));
    mvc.perform(post("/api/v1/listings/" + UUID.randomUUID() + "/approve"))
      .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void rejectRequiresReason() throws Exception {
    when(listings.reject(any(), any())).thenThrow(
      new ResponseStatusException(HttpStatus.BAD_REQUEST, "reason is required"));
    mvc.perform(post("/api/v1/listings/" + UUID.randomUUID() + "/reject")
      .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
      .andExpect(status().isBadRequest());
  }

  @Test
  void ownerListingsAreListed() throws Exception {
    when(listings.listByOwner("user-1", null)).thenReturn(List.of(draft()));
    mvc.perform(get("/api/v1/listings").param("ownerUserId", "user-1")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].status").value("DRAFT"));
  }

  @Test
  void mediaUploadUrlIsIssued() throws Exception {
    when(media.uploadUrl(any(), any())).thenReturn(
      new UploadUrlResponse("listings/x/1.jpg", "https://s3/presigned", Instant.now()));
    mvc.perform(post("/api/v1/listings/" + UUID.randomUUID() + "/media:upload-url")
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"mediaType\":\"IMAGE\",\"contentType\":\"image/jpeg\",\"sizeBytes\":1024}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.objectKey").value("listings/x/1.jpg"));
  }
}
