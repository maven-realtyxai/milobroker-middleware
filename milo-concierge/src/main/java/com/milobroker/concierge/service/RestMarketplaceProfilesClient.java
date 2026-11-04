package com.milobroker.concierge.service;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
/**
 * Persists user records through the Marketplace API. Concierge triages and
 * validates; it never touches bizdb directly. Parses JSON-P manually.
 */
@Service
public class RestMarketplaceProfilesClient implements MarketplaceProfilesClient {
  private final RestClient rest;
  public RestMarketplaceProfilesClient(RestClient.Builder builder,
      @Value("${milobroker.downstream.marketplace-base-url:http://localhost:8082}") String baseUrl) {
    this.rest = builder.baseUrl(baseUrl).build();
  }
  @Override
  public ProfileDto fetch(String channelUserId) {
    String json = rest.get().uri("/api/v1/onboarding/profiles?channelUserId={id}", channelUserId)
      .retrieve().body(String.class);
    return toProfile(Jsons.parseObject(json, "Marketplace profile"));
  }
  @Override
  public boolean claimWelcome(String channelUserId, String displayName) {
    String response = rest.post().uri(uriBuilder -> uriBuilder
        .path("/api/v1/onboarding/profiles/welcome")
        .queryParam("channelUserId", channelUserId)
        .queryParamIfPresent("displayName", java.util.Optional.ofNullable(displayName))
        .build())
      .retrieve().body(String.class);
    return Boolean.parseBoolean(response);
  }
  @Override
  public void releaseWelcome(String channelUserId) {
    rest.delete().uri(uriBuilder -> uriBuilder
        .path("/api/v1/onboarding/profiles/welcome")
        .queryParam("channelUserId", channelUserId)
        .build())
      .retrieve().toBodilessEntity();
  }
  @Override
  public ProfileDto save(String channelUserId, String displayName, String intent,
      String stateCode, String cityCode, boolean termsAccepted) {
    String payload = Json.createObjectBuilder()
      .add("displayName", displayName)
      .add("intent", intent)
      .add("stateCode", stateCode)
      .add("cityCode", cityCode)
      .add("termsAccepted", termsAccepted)
      .build().toString();
    String json = rest.post().uri("/api/v1/onboarding/profiles?channelUserId={id}", channelUserId)
      .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
      .body(payload)
      .retrieve().body(String.class);
    return toProfile(Jsons.parseObject(json, "Marketplace profile"));
  }
  private static ProfileDto toProfile(JsonObject row) {
    return new ProfileDto(
      Jsons.string(row, "channelUserId"),
      Jsons.string(row, "displayName"),
      Jsons.string(row, "intent"),
      Jsons.string(row, "stateCode"),
      Jsons.string(row, "cityCode"),
      row.containsKey("completed") && !row.isNull("completed") && row.getBoolean("completed"));
  }
}
