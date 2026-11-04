package com.milobroker.concierge.api;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.ConciergeMessageService;
import com.milobroker.concierge.service.InputGuardrailService;
import com.milobroker.concierge.service.ConciergeMessageService.AcceptedMessage;
import jakarta.json.JsonObject;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Accepts normalized messages, persists the envelope, and routes next. Speaks pure JSON-P. */
@RestController @RequestMapping("/api/v1/concierge/messages")
public class ConciergeController {
  private final ConciergeMessageService messages;
  private final InputGuardrailService guardrail;
  public ConciergeController(ConciergeMessageService messages, InputGuardrailService guardrail) {
    this.messages = messages;
    this.guardrail = guardrail;
  }
  /** Records the transport-neutral envelope; duplicates of a channel message id are acknowledged. */
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
    UUID session = sessionId == null ? null : parseUuid(sessionId);
    InputGuardrailService.Verdict verdict = guardrail.evaluateText(text);
    if (!verdict.allowed()) {
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, verdict.clarity());
    }
    AcceptedMessage accepted = messages.accept(session, Jsons.string(body, "channel"),
      Jsons.string(body, "channelUserId"), "TEXT", bodyJson, channelMessageId);
    String payload = "{"
      + "\"eventId\":\"" + accepted.eventId() + "\","
      + "\"sessionId\":\"" + accepted.sessionId() + "\","
      + "\"status\":\"" + accepted.status() + "\","
      + "\"acceptedAt\":\"" + accepted.acceptedAt() + "\""
      + "}";
    HttpStatus status = "RECEIVED".equals(accepted.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
    return ResponseEntity.status(status).body(payload);
  }
  private UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId is not a UUID", ex);
    }
  }
}
