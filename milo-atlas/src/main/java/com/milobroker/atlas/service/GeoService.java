package com.milobroker.atlas.service;
import com.milobroker.atlas.dto.GeoDtos.CityDto;
import com.milobroker.atlas.dto.GeoDtos.CountryDto;
import com.milobroker.atlas.dto.GeoDtos.StateDto;
import com.milobroker.atlas.repository.CityRepository;
import com.milobroker.atlas.repository.CountryRepository;
import com.milobroker.atlas.repository.GeoStateRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
/** Read-only geography catalog for Concierge prompts and validation. */
@Service @Transactional(readOnly = true)
public class GeoService {
  private final CountryRepository countries;
  private final GeoStateRepository states;
  private final CityRepository cities;
  public GeoService(CountryRepository countries, GeoStateRepository states, CityRepository cities) {
    this.countries = countries;
    this.states = states;
    this.cities = cities;
  }
  public List<CountryDto> countries() {
    return countries.findAllByOrderByCountryNameAsc().stream().map(CountryDto::from).toList();
  }
  public List<StateDto> states(String countryShortForm) {
    String form = countryShortForm == null ? "IN" : countryShortForm.trim().toUpperCase();
    if (form.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Country code must not be blank");
    }
    return states.findByCountry_ShortFormOrderByStateNameAsc(form)
      .stream().map(StateDto::from).toList();
  }
  public List<CityDto> cities(String state) {
    if (state == null || state.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Query parameter 'state' is required");
    }
    return cities.findByState_StateCodeOrderByCityNameAsc(
        state.trim().toUpperCase(), PageRequest.of(0, 500))
      .stream().map(CityDto::from).toList();
  }
}
