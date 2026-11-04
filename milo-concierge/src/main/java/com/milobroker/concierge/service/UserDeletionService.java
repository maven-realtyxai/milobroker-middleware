package com.milobroker.concierge.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Confirmation and local cleanup for a WhatsApp sender; Marketplace owns business data. */
@Service
public class UserDeletionService {
  private final JdbcTemplate db;
  private final RestClient marketplace;
  private final String secret;
  public UserDeletionService(JdbcTemplate db, RestClient.Builder builder,
      @Value("${milobroker.downstream.marketplace-base-url:http://localhost:8082}") String baseUrl,
      @Value("${milobroker.internal.deletion-secret:}") String secret) {
    this.db = db; this.marketplace = builder.baseUrl(baseUrl).build(); this.secret = secret;
  }
  public String request(String userId) {
    if (secret == null || secret.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Deletion is not configured");
    }
    byte[] bytes = new byte[8];
    new java.security.SecureRandom().nextBytes(bytes);
    String code = java.util.HexFormat.of().formatHex(bytes).toUpperCase(java.util.Locale.ROOT);
    db.update("INSERT INTO deletion_confirmations(channel_user_id, confirmation_code, expires_at) VALUES (?, ?, now() + interval '15 minutes') "
      + "ON CONFLICT(channel_user_id) DO UPDATE SET confirmation_code = EXCLUDED.confirmation_code, expires_at = EXCLUDED.expires_at", userId, code);
    return code;
  }
  public void cancel(String userId) {
    db.update("DELETE FROM deletion_confirmations WHERE channel_user_id = ?", userId);
  }
  @Transactional
  public boolean confirm(String userId, String code) {
    List<Boolean> pending = db.query("SELECT expires_at > now() AND confirmation_code = ? FROM deletion_confirmations "
      + "WHERE channel_user_id = ? FOR UPDATE", (rs, n) -> rs.getBoolean(1), code, userId);
    if (pending.isEmpty() || !pending.get(0)) return false;
    if (secret == null || secret.isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Deletion is not configured");
    }
    marketplace.delete().uri("/api/v1/onboarding/users/{id}", userId)
      .header("X-Internal-Deletion-Key", secret).retrieve().toBodilessEntity();
    db.update("DELETE FROM model_executions WHERE session_id IN "
      + "(SELECT id FROM conversation_sessions WHERE lower(channel) = 'whatsapp' AND channel_user_id = ?) "
      + "OR inbound_event_id IN (SELECT id FROM inbound_events WHERE session_id IN "
      + "(SELECT id FROM conversation_sessions WHERE lower(channel) = 'whatsapp' AND channel_user_id = ?))", userId, userId);
    db.update("DELETE FROM inbound_events WHERE session_id IN "
      + "(SELECT id FROM conversation_sessions WHERE lower(channel) = 'whatsapp' AND channel_user_id = ?)", userId);
    db.update("DELETE FROM conversation_sessions WHERE lower(channel) = 'whatsapp' AND channel_user_id = ?", userId);
    cancel(userId);
    return true;
  }
}
