package com.milobroker.marketplace.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.milobroker.marketplace.service.LeadService;
import com.milobroker.marketplace.service.LeadService.LeadRecord;
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

/** HTTP contracts for leads. Service mocked; no database required. */
@WebMvcTest(LeadController.class)
class LeadWebTest {
  @Autowired MockMvc mvc;
  @MockitoBean LeadService leads;

  private LeadRecord requested() {
    return new LeadRecord(UUID.randomUUID(), UUID.randomUUID(), "buyer-1",
      "Interested", "REQUESTED", null, Instant.now(), Instant.now());
  }

  @Test
  void requestCreatesLead() throws Exception {
    when(leads.request(any())).thenReturn(requested());
    mvc.perform(post("/api/v1/leads:request").contentType(MediaType.APPLICATION_JSON).content(
      "{\"listingId\":\"" + UUID.randomUUID() + "\",\"requesterChannelUserId\":\"buyer-1\","
        + "\"message\":\"Interested\"}"))
      .andExpect(status().isCreated())
      .andExpect(jsonPath("$.status").value("REQUESTED"));
  }

  @Test
  void unknownLeadIsNotFound() throws Exception {
    when(leads.get(any())).thenThrow(
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found"));
    mvc.perform(get("/api/v1/leads/" + UUID.randomUUID())).andExpect(status().isNotFound());
  }

  @Test
  void consentWithoutGrantIsUnprocessable() throws Exception {
    when(leads.consent(any(), any())).thenThrow(new ResponseStatusException(
      HttpStatus.UNPROCESSABLE_ENTITY, "Explicit consent (granted=true) is required"));
    mvc.perform(post("/api/v1/leads/" + UUID.randomUUID() + ":consent")
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"requesterChannelUserId\":\"buyer-1\",\"granted\":false}"))
      .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void declineMovesToDeclined() throws Exception {
    LeadRecord declined = new LeadRecord(UUID.randomUUID(), UUID.randomUUID(), "buyer-1",
      null, "DECLINED", null, Instant.now(), Instant.now());
    when(leads.decline(any(), any())).thenReturn(declined);
    mvc.perform(post("/api/v1/leads/" + declined.id() + ":decline")
      .contentType(MediaType.APPLICATION_JSON)
      .content("{\"requesterChannelUserId\":\"buyer-1\"}"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.status").value("DECLINED"));
  }

  @Test
  void leadsAreListedByRequester() throws Exception {
    when(leads.list(eq("buyer-1"), eq(null))).thenReturn(List.of(requested()));
    mvc.perform(get("/api/v1/leads").param("requester", "buyer-1"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].status").value("REQUESTED"));
  }
}
