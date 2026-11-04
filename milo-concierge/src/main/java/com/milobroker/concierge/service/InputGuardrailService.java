package com.milobroker.concierge.service;

import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Deterministic input guardrail for the prototype concierge.
 * Plain keyword rules, no external calls. Allows property talk, onboarding
 * talk, greetings that start onboarding, and voice/audio messages; blocks
 * harmful and off-topic input with a resend-with-clarity verdict.
 */
@Service
public class InputGuardrailService {
  private static final Logger log = LoggerFactory.getLogger(InputGuardrailService.class);

  public enum Decision { ALLOW, GREETING, VOICE, OFF_TOPIC, HARMFUL }

  public record Verdict(Decision decision, String clarity) {
    public boolean allowed() {
      return decision == Decision.ALLOW || decision == Decision.GREETING || decision == Decision.VOICE;
    }
  }

  public static final String OFF_TOPIC_CLARITY = "I can help with property and onboarding topics "
    + "- rentals, purchases, or listing your property. "
    + "Please resend with clarity on what you are looking for (city, locality, budget, bedrooms).";
  public static final String HARMFUL_CLARITY = "I can't help with that. "
    + "I can help with property and onboarding topics "
    + "- rentals, purchases, or listing your property.";

  private static final Set<String> ALLOW_TERMS = Set.of(
    "rent", "rental", "buy", "purchase", "sale", "sell", "list", "listing",
    "plot", "flat", "apartment", "house", "villa", "pg", "room", "floor",
    "bedroom", "bhk", "bath", "furnish", "kitchen", "balcony", "parking",
    "price", "budget", "lakh", "crore", "deposit", "agreement", "loan", "emi",
    "locality", "location", "city", "state", "tenant", "owner", "broker", "agent",
    "move", "shift", "visit", "site", "possession",
    "find a rental", "buy a property", "list a property", "i'm an agent", "im an agent",
    "onboard", "register", "sign up", "signup", "details", "form", "flow", "name");
  private static final Set<String> GREETINGS = Set.of(
    "hi", "hello", "hey", "namaste", "namaskar", "vanakkam",
    "good morning", "good afternoon", "good evening", "salaam", "adaab");
  private static final Set<String> HARMFUL_TERMS = Set.of(
    "kill", "murder", "shoot", "stab", "bomb", "attack", "assault", "beat up",
    "suicide", "self-harm", "self harm", "kill myself", "end my life", "cut myself",
    "porn", "nude", "molest", "rape",
    "steal", "rob", "bribe", "forge", "fraud", "scam", "drug deal", "sell drugs",
    "counterfeit", "hack into",
    "ignore previous instructions", "ignore all instructions", "disregard instructions",
    "system prompt", "jailbreak", "dan mode", "pretend you are", "bypass your rules",
    "override your instructions");

  public Verdict evaluateText(String text) {
    return evaluate("text", text);
  }

  /**
   * Slot-fill variant for short post-flow answers. HARMFUL stays blocking and
   * VOICE/GREETING/ALLOW pass through untouched; an OFF_TOPIC verdict is
   * relaxed to ALLOW when the value is slot-shaped (a place-like name, a
   * number, or a yes/no answer) so bare slot values are never blocked.
   */
  public Verdict evaluateForSlot(String slotName, String messageType, String text) {
    if (isVoiceType(messageType)) {
      return new Verdict(Decision.VOICE, null);
    }
    String norm = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    if (norm.isBlank()) {
      return new Verdict(Decision.ALLOW, null);
    }
    if (containsAny(norm, HARMFUL_TERMS)) {
      log.warn("Guardrail blocked harmful slot message");
      return new Verdict(Decision.HARMFUL, HARMFUL_CLARITY);
    }
    Verdict base = evaluate(messageType, text);
    if (base.decision() != Decision.OFF_TOPIC) {
      return base;
    }
    if (isSlotShaped(norm)) {
      return new Verdict(Decision.ALLOW, null);
    }
    return base;
  }

  private static final Set<String> YES_NO = Set.of(
    "yes", "y", "yeah", "yep", "ok", "okay", "accept", "accepted", "confirm",
    "no", "n", "nope", "cancel");

  private static boolean isSlotShaped(String norm) {
    if (YES_NO.contains(norm)) {
      return true;
    }
    String compact = norm.replaceAll("[\\s,₹$]", "");
    if (compact.matches("\\d+(\\.\\d+)?(lakh|lac|crore|cr|k)?")) {
      return true;
    }
    if (norm.matches("[a-z][a-z .,\\-']{1,39}")) {
      return true;
    }
    return false;
  }

  public Verdict evaluate(String messageType, String text) {
    if (isVoiceType(messageType)) {
      return new Verdict(Decision.VOICE, null);
    }
    String norm = text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    if (norm.isBlank()) {
      return new Verdict(Decision.ALLOW, null);
    }
    if (containsAny(norm, HARMFUL_TERMS)) {
      log.warn("Guardrail blocked harmful inbound message");
      return new Verdict(Decision.HARMFUL, HARMFUL_CLARITY);
    }
    if (containsAny(norm, OFF_TOPIC_TERMS)) {
      return new Verdict(Decision.OFF_TOPIC, OFF_TOPIC_CLARITY);
    }
    if (isGreeting(norm)) {
      return new Verdict(Decision.GREETING, null);
    }
    if (containsAny(norm, ALLOW_TERMS)) {
      return new Verdict(Decision.ALLOW, null);
    }
    return new Verdict(Decision.OFF_TOPIC, OFF_TOPIC_CLARITY);
  }

  public static boolean isVoiceType(String messageType) {
    if (messageType == null) {
      return false;
    }
    String t = messageType.trim().toLowerCase(Locale.ROOT);
    return t.equals("audio") || t.equals("voice") || t.equals("ptt") || t.equals("voice-note");
  }

  private static final Set<String> OFF_TOPIC_TERMS = Set.of(
    "stocks", "stock market", "share market", "mutual fund", "crypto", "bitcoin",
    "gamble", "gambling", "lottery", "casino", "trading", "forex");

  private static boolean isGreeting(String norm) {
    for (String g : GREETINGS) {
      if (norm.equals(g) || norm.startsWith(g + " ") || norm.startsWith(g + ",")
        || norm.startsWith(g + "!")) {
        return norm.length() <= 40;
      }
    }
    return false;
  }

  private static boolean containsAny(String norm, Set<String> terms) {
    for (String term : terms) {
      if (norm.contains(term)) {
        return true;
      }
    }
    return false;
  }
}
