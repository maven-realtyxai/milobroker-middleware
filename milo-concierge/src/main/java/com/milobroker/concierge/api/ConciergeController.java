package com.milobroker.concierge.api;
import com.milobroker.concierge.json.Jsons;
import jakarta.json.JsonObject;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Accepts normalized messages before session resolution and safe routing. Speaks pure JSON-P. */
@RestController @RequestMapping("/api/v1/concierge/messages")
public class ConciergeController {
  /** Records the transport-neutral envelope used by the walking skeleton. */
  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<String> accept(@RequestBody String bodyJson) {
    JsonObject body = Jsons.parseObject(bodyJson, "Inbound message");
    String text = Jsons.string(body, "text");
    if (text == null || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must not be blank");
    }
    if (text.length() > 4096) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text must be at most 4096 characters");
    }
    String channelMessageId = Jsons.string(body, "channelMessageId");
    if (channelMessageId == null || channelMessageId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelMessageId must not be blank");
    }
    if (channelMessageId.length() > 128) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "channelMessageId must be at most 128 characters");
    }
    String sessionId = Jsons.string(body, "sessionId");
    UUID session = sessionId == null ? UUID.randomUUID() : parseUuid(sessionId);
    UUID eventId = UUID.randomUUID();
    // Raw WhatsApp payloads are adapted and verified before this contract.
    return ResponseEntity.status(HttpStatus.ACCEPTED).body("{"
      + "\"eventId\":\"" + eventId + "\","
      + "\"sessionId\":\"" + session + "\","
      + "\"status\":\"RECEIVED\","
      + "\"acceptedAt\":\"" + Instant.now() + "\""
      + "}");
  }
  private UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is not a UUID", ex);
    }
  }
}
