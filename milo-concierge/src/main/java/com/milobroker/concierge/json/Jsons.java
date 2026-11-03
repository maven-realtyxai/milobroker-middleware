package com.milobroker.concierge.json;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import java.io.StringReader;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Tiny guards over JSON-P navigation. Missing members yield null, never exceptions. */
public final class Jsons {
  private Jsons() {}
  public static JsonObject parseObject(String json, String what) {
    try (JsonReader reader = Json.createReader(new StringReader(json))) {
      return reader.readObject();
    } catch (JsonException | IllegalStateException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " is not a JSON object", ex);
    }
  }
  public static String string(JsonObject obj, String name) {
    if (obj == null || !obj.containsKey(name)) {
      return null;
    }
    JsonValue value = obj.get(name);
    if (value == null || value == JsonValue.NULL) {
      return null;
    }
    return value.getValueType() == JsonValue.ValueType.STRING ? obj.getString(name) : value.toString();
  }
  public static JsonObject object(JsonObject obj, String name) {
    if (obj == null || !obj.containsKey(name) || obj.isNull(name)) {
      return null;
    }
    try {
      return obj.getJsonObject(name);
    } catch (ClassCastException ex) {
      return null;
    }
  }
  public static JsonArray array(JsonObject obj, String name) {
    if (obj == null || !obj.containsKey(name) || obj.isNull(name)) {
      return Json.createArrayBuilder().build();
    }
    try {
      return obj.getJsonArray(name);
    } catch (ClassCastException ex) {
      return Json.createArrayBuilder().build();
    }
  }
}
