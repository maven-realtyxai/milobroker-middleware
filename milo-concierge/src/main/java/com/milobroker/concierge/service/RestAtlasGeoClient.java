package com.milobroker.concierge.service;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasCity;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasState;
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
/** HTTP client for Atlas geo endpoints. Parses JSON-P manually. Never touches Atlas tables directly. */
@Service
public class RestAtlasGeoClient implements AtlasGeoClient {
  private final RestClient rest;
  public RestAtlasGeoClient(RestClient.Builder builder,
      @Value("${milobroker.downstream.atlas-base-url:http://localhost:8083}") String baseUrl) {
    this.rest = builder.baseUrl(baseUrl).build();
  }
  @Override
  public List<AtlasState> states() {
    String json = rest.get().uri("/api/v1/geo/states?countryIso2=IN")
      .retrieve().body(String.class);
    List<AtlasState> out = new ArrayList<>();
    for (JsonObject row : topArray(json)) {
      out.add(new AtlasState(Jsons.string(row, "code"), Jsons.string(row, "displayName")));
    }
    return out;
  }
  @Override
  public List<AtlasCity> cities(String stateCode) {
    String json = rest.get().uri("/api/v1/geo/cities?state={code}", stateCode)
      .retrieve().body(String.class);
    List<AtlasCity> out = new ArrayList<>();
    for (JsonObject row : topArray(json)) {
      out.add(new AtlasCity(Jsons.string(row, "code"), Jsons.string(row, "displayName"),
        Jsons.string(row, "stateCode")));
    }
    return out;
  }
  /** Atlas returns top-level JSON arrays; keep only the object rows. */
  private static List<JsonObject> topArray(String json) {
    JsonArray raw;
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      raw = reader.readArray();
    } catch (JsonException ex) {
      throw new IllegalStateException("Atlas returned an unreadable geo payload", ex);
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
