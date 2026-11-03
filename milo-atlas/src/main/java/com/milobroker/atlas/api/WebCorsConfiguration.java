package com.milobroker.atlas.api;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Restricts browser-readable Atlas endpoints to the configured site origins. */
@Configuration
public class WebCorsConfiguration implements WebMvcConfigurer {
  private final List<String> allowedOrigins;

  public WebCorsConfiguration(@Value("${milobroker.cors.allowed-origins:https://milobroker.com,https://www.milobroker.com,http://localhost:5173,http://127.0.0.1:4173}") String origins) {
    this.allowedOrigins = Arrays.stream(origins.split(","))
      .map(String::trim).filter(origin -> !origin.isEmpty()).toList();
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/contact")
      .allowedOrigins(allowedOrigins.toArray(String[]::new))
      .allowedMethods("GET", "OPTIONS")
      .allowedHeaders("Accept")
      .maxAge(3600);
  }
}
