package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Output guardrail: hard violations fall back, soft issues are sanitized. */
class OutputGuardrailTest {
  private OutputGuardrailService guardrail;

  @BeforeEach
  void setup() {
    guardrail = new OutputGuardrailService();
  }

  @Test
  void phoneNumberFallsBack() {
    assertNull(guardrail.sanitize("Call me at 98765 43210 for details", "Ravi"));
  }

  @Test
  void emailFallsBack() {
    assertNull(guardrail.sanitize("Mail me at ravi@example.com for photos", "Ravi"));
  }

  @Test
  void offTopicFallsBack() {
    assertNull(guardrail.sanitize("You should buy bitcoin for quick gains", "Ravi"));
  }

  @Test
  void longReplyIsTruncatedToSixtyWords() {
    StringBuilder raw = new StringBuilder();
    for (int i = 1; i <= 80; i++) {
      if (i > 1) {
        raw.append(' ');
      }
      raw.append("word").append(i);
    }
    String safe = guardrail.sanitize(raw.toString(), "Ravi");
    String[] words = safe.split("\\s+");
    assertTrue(words.length <= 60, "expected <=60 words but got " + words.length);
    assertTrue(safe.endsWith("..."));
  }

  @Test
  void exactlySixtyWordsPassesThrough() {
    StringBuilder raw = new StringBuilder();
    for (int i = 1; i <= 60; i++) {
      if (i > 1) {
        raw.append(' ');
      }
      raw.append("word").append(i);
    }
    assertEquals(raw.toString(), guardrail.sanitize(raw.toString(), "Ravi"));
  }

  @Test
  void linksAreReplaced() {
    String safe = guardrail.sanitize("See https://example.com/flat and www.photos.com/a for photos", "Ravi");
    assertTrue(safe.contains("[link removed]"));
    assertTrue(!safe.contains("https://") && !safe.contains("www.photos.com"));
  }

  @Test
  void emptyFallsBack() {
    assertNull(guardrail.sanitize(null, "Ravi"));
    assertNull(guardrail.sanitize("", "Ravi"));
    assertNull(guardrail.sanitize("   ", "Ravi"));
  }

  @Test
  void normalReplyPassesThrough() {
    assertEquals("Here are 2 options in BLR.",
      guardrail.sanitize("Here are 2 options in BLR.", "Ravi"));
  }

  @Test
  void priceIsNotMistakenForPhone() {
    assertEquals("2BHK for Rs.25000 in BLR.",
      guardrail.sanitize("2BHK for Rs.25000 in BLR.", "Ravi"));
  }
}
