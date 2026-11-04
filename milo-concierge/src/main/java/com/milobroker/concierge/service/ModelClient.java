package com.milobroker.concierge.service;
/** Language-model boundary. Disabled when no key/model is configured. */
public interface ModelClient {
  boolean isConfigured();
  String chat(String user);
  static ModelClient disabled() {
    return new ModelClient() {
      @Override public boolean isConfigured() { return false; }
      @Override public String chat(String user) {
        throw new UnsupportedOperationException("Model is not configured");
      }
    };
  }
}
