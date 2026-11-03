package com.milobroker.concierge.service;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasCity;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasState;
import java.util.List;
/** Reads geo options from Atlas. Codes are stable ids; names are display labels. */
public interface AtlasGeoClient {
  List<AtlasState> states();
  List<AtlasCity> cities(String stateCode);
}
