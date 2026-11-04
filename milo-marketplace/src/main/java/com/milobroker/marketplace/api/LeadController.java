package com.milobroker.marketplace.api;
import com.milobroker.marketplace.service.LeadService;
import com.milobroker.marketplace.service.LeadService.ActorLeadRequest;
import com.milobroker.marketplace.service.LeadService.ConsentLeadRequest;
import com.milobroker.marketplace.service.LeadService.LeadRecord;
import com.milobroker.marketplace.service.LeadService.RequestLeadRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
/** Lead boundary. Internal only; keep behind the proxy like listing writes. */
@RestController @RequestMapping("/api/v1")
public class LeadController {
  private final LeadService leads;
  public LeadController(LeadService leads) {
    this.leads = leads;
  }
  @PostMapping("/leads:request")
  @ResponseStatus(HttpStatus.CREATED)
  public LeadRecord request(@Valid @RequestBody RequestPayload payload) {
    return leads.request(new RequestLeadRequest(payload.listingId(),
      payload.requesterChannelUserId(), payload.message()));
  }
  @PostMapping("/leads/{id}:consent")
  public LeadRecord consent(@PathVariable UUID id,
      @Valid @RequestBody ConsentPayload payload) {
    return leads.consent(id, new ConsentLeadRequest(
      payload.requesterChannelUserId(), payload.granted()));
  }
  @PostMapping("/leads/{id}:decline")
  public LeadRecord decline(@PathVariable UUID id,
      @Valid @RequestBody ActorPayload payload) {
    return leads.decline(id, new ActorLeadRequest(payload.requesterChannelUserId()));
  }
  @PostMapping("/leads/{id}:close")
  public LeadRecord close(@PathVariable UUID id) {
    return leads.close(id);
  }
  @GetMapping("/leads/{id}")
  public LeadRecord get(@PathVariable UUID id) {
    return leads.get(id);
  }
  @GetMapping("/leads")
  public List<LeadRecord> list(@RequestParam(required = false) String requester,
      @RequestParam(required = false) UUID listingId) {
    return leads.list(requester, listingId);
  }
  public record RequestPayload(UUID listingId, String requesterChannelUserId, String message) {}
  public record ConsentPayload(String requesterChannelUserId, Boolean granted) {}
  public record ActorPayload(String requesterChannelUserId) {}
}
