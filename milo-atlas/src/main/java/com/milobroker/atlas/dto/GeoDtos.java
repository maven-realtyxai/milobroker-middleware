package com.milobroker.atlas.dto;
import com.milobroker.atlas.domain.City;
import com.milobroker.atlas.domain.Country;
import com.milobroker.atlas.domain.GeoState;
/** Public projections for the geography catalog. */
public final class GeoDtos {
  private GeoDtos() {}
  public record CountryDto(long id, String shortForm, String countryName, String whatsappNumber) {
    public static CountryDto from(Country c) {
      return new CountryDto(c.getId(), c.getShortForm(), c.getCountryName(), c.getWhatsappNumber());
    }
  }
  public record StateDto(long id, String code, String displayName, String type) {
    public static StateDto from(GeoState s) {
      return new StateDto(s.getId(), s.getStateCode(), s.getStateName(), s.getType());
    }
  }
  public record CityDto(long id, String code, String displayName, String stateCode) {
    public static CityDto from(City c) {
      return new CityDto(c.getId(), c.getCityCode(), c.getCityName(), c.getState().getStateCode());
    }
  }
}
