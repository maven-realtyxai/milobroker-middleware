package com.milobroker.atlas.api;
import com.milobroker.atlas.dto.GeoDtos.CityDto;
import com.milobroker.atlas.dto.GeoDtos.CountryDto;
import com.milobroker.atlas.dto.GeoDtos.StateDto;
import com.milobroker.atlas.service.GeoService;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
/** Thin HTTP adapter over the geography catalog. All queries live in {@link GeoService}. */
@RestController @RequestMapping("/api/v1/geo") @Validated
public class GeoController {
  private final GeoService geo;
  public GeoController(GeoService geo) { this.geo = geo; }
  @GetMapping("/countries")
  public List<CountryDto> countries() { return geo.countries(); }
  @GetMapping("/states")
  public List<StateDto> states(@RequestParam(defaultValue = "IN") String countryIso2) {
    return geo.states(countryIso2);
  }
  @GetMapping("/cities")
  public List<CityDto> cities(@RequestParam String state) { return geo.cities(state); }
}
