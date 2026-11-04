package com.milobroker.concierge.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Output guardrail for model replies. Plain-text only: hard violations
 * (PII, off-topic) yield null so the caller sends the existing fallback,
 * soft issues (links, over-long replies) are sanitized in place.
 */
@Service
public class OutputGuardrailService {
  static final int MAX_WORDS = 60;

  private static final Pattern EMAIL =
    Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern PHONE =
    Pattern.compile("(?:\\+\\d{1,3}[\\s\\-]?)?(?:\\d[\\d\\s\\-.()]*){10,}");
  private static final Pattern LINK =
    Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

  private static final Set<String> OFF_TOPIC_TERMS = Set.of(
    "stocks", "stock market", "share market", "mutual fund", "crypto", "bitcoin",
    "gamble", "gambling", "lottery", "casino", "trading", "forex",
    "math homework", "homework");

  /**
   * Returns the safe reply to send, or null when the caller must fall back.
   * Null/blank input is a hard violation (nothing safe to send).
   */
  public String sanitize(String raw, String greet) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String norm = raw.toLowerCase(Locale.ROOT);
    if (EMAIL.matcher(raw).find() || PHONE.matcher(raw).find()) {
      return null;
    }
    for (String term : OFF_TOPIC_TERMS) {
      if (norm.contains(term)) {
        return null;
      }
    }
    String cleaned = LINK.matcher(raw).replaceAll("[link removed]").trim();
    if (cleaned.isBlank()) {
      return null;
    }
    String[] words = cleaned.split("\\s+");
    if (words.length > MAX_WORDS) {
      StringBuilder kept = new StringBuilder();
      for (int i = 0; i < MAX_WORDS; i++) {
        if (i > 0) {
          kept.append(' ');
        }
        kept.append(words[i]);
      }
      kept.append("...");
      return kept.toString();
    }
    return cleaned;
  }
}
