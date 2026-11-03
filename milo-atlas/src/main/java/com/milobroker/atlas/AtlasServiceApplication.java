package com.milobroker.atlas;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts Milo Atlas, the static reference middleware for geography and onboarding catalogs. */
@SpringBootApplication
public class AtlasServiceApplication {
  public static void main(String[] args) {
    // PostgreSQL JDBC sends the JVM timezone in its initial connection packet.
    // Use UTC consistently instead of inheriting host-specific timezone aliases.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(AtlasServiceApplication.class, args);
  }
}
