package com.milobroker.concierge.dto;
/** DTOs exchanged with Atlas geo endpoints and WhatsApp Flows. Codes are option ids, names are labels. */
public final class OnboardingDtos {
  private OnboardingDtos() {}
  public record OptionDto(String id, String title) {}
  public record ProfileDto(String channelUserId, String displayName, String intent,
      String stateCode, String cityCode, boolean completed) {}
  public record CompletionRequest(String displayName, String intent, String stateCode, String cityCode,
      Boolean termsAccepted) {}
  public record AtlasState(String code, String displayName) {}
  public record AtlasCity(String code, String displayName, String stateCode) {}
}
