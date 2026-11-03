package com.milobroker.atlas.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.milobroker.atlas.dto.GeoDtos.CityDto;
import com.milobroker.atlas.dto.GeoDtos.CountryDto;
import com.milobroker.atlas.service.GeoService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

/**
 * HTTP contract for the Atlas geo APIs. The service is mocked; no database required.
 */
@WebMvcTest({GeoController.class, AtlasApiExceptionHandler.class})
class AtlasWebContractTest {
  @Autowired MockMvc mvc;
  @MockitoBean GeoService geo;

  @Test
  void countriesListsSeededRows() throws Exception {
    when(geo.countries()).thenReturn(
      List.of(new CountryDto(1, "IN", "India", "+91")));
    mvc.perform(get("/api/v1/geo/countries")).andExpect(status().isOk())
      .andExpect(jsonPath("$[0].shortForm").value("IN"));
  }

  @Test
  void citiesRequireState() throws Exception {
    mvc.perform(get("/api/v1/geo/cities")).andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.status").value(400));
    when(geo.cities(" ")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "required"));
    mvc.perform(get("/api/v1/geo/cities").param("state", " "))
      .andExpect(status().isBadRequest());
  }

  @Test
  void citiesReturnScopedRows() throws Exception {
    when(geo.cities("KA")).thenReturn(
      List.of(new CityDto(1, "BLR", "Bengaluru", "KA")));
    mvc.perform(get("/api/v1/geo/cities").param("state", "KA"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$[0].code").value("BLR"));
  }
}
