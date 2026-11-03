package com.milobroker.concierge;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** Starts Milo Concierge, owner of WhatsApp sessions, guardrails and safe routing. */
@SpringBootApplication
public class ConciergeServiceApplication { public static void main(String[] args) { SpringApplication.run(ConciergeServiceApplication.class, args); } }
