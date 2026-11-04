package com.milobroker.atlas.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.milobroker.atlas.service.ContactService;
import com.milobroker.atlas.service.ContactService.ContactResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP contract for the public contact lookup. The service is mocked; no database required.
 */
@WebMvcTest({ContactController.class, AtlasApiExceptionHandler.class})
class ContactWebContractTest {
  @Autowired MockMvc mvc;
  @MockitoBean ContactService contacts;

  @Test
  void contactReturnsDialingPrefixAndBusinessNumber() throws Exception {
    when(contacts.contact("IN")).thenReturn(new ContactResponse("+91", "8123995310"));
    mvc.perform(get("/api/contact").param("countryIso2", "IN")).andExpect(status().isOk())
      .andExpect(jsonPath("$.countryCode").value("+91"))
      .andExpect(jsonPath("$.whatsappPhoneNumber").value("8123995310"));
  }

  @Test
  void unknownCountryIsNotFound() throws Exception {
    when(contacts.contact("XX")).thenThrow(
      new ResponseStatusException(HttpStatus.NOT_FOUND, "Country is not configured"));
    mvc.perform(get("/api/contact").param("countryIso2", "XX")).andExpect(status().isNotFound())
      .andExpect(jsonPath("$.status").value(404));
  }
}
