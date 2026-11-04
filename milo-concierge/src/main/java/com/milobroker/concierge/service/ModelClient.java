package com.milobroker.concierge.service;
/** Language-model boundary. Disabled when no key/model is configured. */
public interface ModelClient {
  enum Relevance { PROPERTY, OTHER, UNKNOWN }
  boolean isConfigured();
  String chat(String user);
  /** Classifies ambiguous text in context; unavailable or inconclusive is UNKNOWN. */
  default Relevance classifyProperty(String text, String history) { return Relevance.UNKNOWN; }
  static ModelClient disabled() {
    return new ModelClient() {
      @Override public boolean isConfigured() { return false; }
      @Override public String chat(String user) {
        throw new UnsupportedOperationException("Model is not configured");
      }
    };
  }
}
