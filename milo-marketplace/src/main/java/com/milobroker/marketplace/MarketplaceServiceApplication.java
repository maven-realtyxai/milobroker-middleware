package com.milobroker.marketplace;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Starts Marketplace, owner of listings, search and lead rules. */
@SpringBootApplication
public class MarketplaceServiceApplication {
  public static void main(String[] args) {
    // PostgreSQL JDBC sends the JVM timezone in its initial connection packet.
    // Use UTC consistently instead of inheriting host-specific timezone aliases.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(MarketplaceServiceApplication.class, args);
  }
}
