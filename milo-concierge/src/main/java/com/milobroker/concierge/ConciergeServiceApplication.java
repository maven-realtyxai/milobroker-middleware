package com.milobroker.concierge;
import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Starts Milo Concierge, owner of WhatsApp sessions, guardrails and safe routing. */
@SpringBootApplication
public class ConciergeServiceApplication {
  public static void main(String[] args) {
    // PostgreSQL JDBC sends the JVM timezone in its initial connection packet.
    // Use UTC consistently instead of inheriting host-specific timezone aliases.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    SpringApplication.run(ConciergeServiceApplication.class, args);
  }
}
