package com.milobroker.concierge.service;

import com.milobroker.concierge.json.Jsons;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Marketplace listings over HTTP. Same JSON-P + RestClient style as the
 * profiles client. Counts ACTIVE listings for the 5-active fail-fast rule.
 */
@Service
public class RestMarketplaceListingsClient implements MarketplaceListingsClient {
  private final RestClient rest;

  public RestMarketplaceListingsClient(RestClient.Builder builder,
      @Value("${milobroker.marketplace.base-url:${milobroker.downstream.marketplace-base-url:http://localhost:8082}}")
      String baseUrl) {
    this.rest = builder.baseUrl(baseUrl).build();
  }

  @Override
  public String createListing(String ownerUserId, String title, String description,
      String listingType, String propertyType, String cityCode, String localityCode,
      Integer bedrooms, Long priceInr) {
    String payload = Json.createObjectBuilder()
      .add("ownerUserId", ownerUserId)
      .add("title", title)
      .add("description", description)
      .add("listingType", listingType)
      .add("propertyType", propertyType)
      .add("cityCode", cityCode)
      .add("localityCode", localityCode)
      .add("bedrooms", bedrooms == null ? 0 : bedrooms)
      .add("priceInr", priceInr == null ? 0 : priceInr)
      .build().toString();
    String json = rest.post().uri("/api/v1/listings")
      .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
      .body(payload)
      .retrieve().body(String.class);
    String id = Jsons.string(Jsons.parseObject(json, "Marketplace listing"), "id");
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("Marketplace returned a listing without an id");
    }
    return id;
  }

  @Override
  public int activeCount(String ownerUserId) {
    String json = rest.get().uri(uriBuilder -> uriBuilder
        .path("/api/v1/listings")
        .queryParam("ownerUserId", ownerUserId)
        .queryParam("status", "ACTIVE")
        .build())
      .retrieve().body(String.class);
    return topArray(json).size();
  }

  private static List<JsonObject> topArray(String json) {
    JsonArray raw;
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      raw = reader.readArray();
    } catch (JsonException ex) {
      throw new IllegalStateException("Marketplace returned an unreadable listings payload", ex);
    }
    List<JsonObject> out = new ArrayList<>(raw.size());
    for (JsonValue value : raw) {
      if (value instanceof JsonObject row) {
        out.add(row);
      }
    }
    return out;
  }
}
