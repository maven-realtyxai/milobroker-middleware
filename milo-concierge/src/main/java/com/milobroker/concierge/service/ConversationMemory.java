package com.milobroker.concierge.service;

import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** A small, bounded history in the existing session context; other flow data is preserved. */
@Service
public class ConversationMemory {
  private final ConversationSessionRepository sessions;
  public ConversationMemory(ConversationSessionRepository sessions) { this.sessions = sessions; }

  public String history(String from) {
    return sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .map(this::read).orElse(List.of()).stream().reduce("", (a, b) -> a + b + "\n");
  }

  public String locality(String from) {
    return sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .map(session -> Jsons.string(root(session), "searchLocality")).orElse(null);
  }

  public JsonObject searchFilters(String from) {
    return sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .map(session -> Jsons.object(root(session), "searchFilters")).orElse(null);
  }

  public void saveSearchFilters(String from, JsonObject filters) {
    ConversationSession session = sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .orElseGet(() -> {
        var fresh = new ConversationSession();
        fresh.setChannel("whatsapp"); fresh.setChannelUserId(from); fresh.setState("OPEN");
        return fresh;
      });
    var updated = Json.createObjectBuilder(root(session));
    if (filters == null) updated.remove("searchFilters");
    else updated.add("searchFilters", filters);
    session.setContext(updated.build().toString());
    sessions.save(session);
  }

  public String pendingSearch(String from) {
    return sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .map(session -> Jsons.string(root(session), "pendingSearch")).orElse(null);
  }

  public void savePendingSearch(String from, String query) {
    ConversationSession session = sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .orElseGet(() -> {
        ConversationSession fresh = new ConversationSession();
        fresh.setChannel("whatsapp"); fresh.setChannelUserId(from); fresh.setState("OPEN");
        return fresh;
      });
    var updated = Json.createObjectBuilder(root(session));
    if (query == null) updated.remove("pendingSearch");
    else updated.add("pendingSearch", bounded(query));
    session.setContext(updated.build().toString());
    sessions.save(session);
  }

  public void rememberLocality(String from, String locality) {
    ConversationSession session = sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .orElseGet(() -> {
        ConversationSession fresh = new ConversationSession();
        fresh.setChannel("whatsapp"); fresh.setChannelUserId(from); fresh.setState("OPEN");
        return fresh;
      });
    session.setContext(Json.createObjectBuilder(root(session)).add("searchLocality", locality).build().toString());
    sessions.save(session);
  }

  public void record(String from, String user, String assistant) {
    ConversationSession session = sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", from)
      .orElseGet(() -> {
        ConversationSession fresh = new ConversationSession();
        fresh.setChannel("whatsapp"); fresh.setChannelUserId(from); fresh.setState("OPEN");
        return fresh;
      });
    JsonObject root = root(session);
    List<String> turns = read(session);
    turns.add("User: " + bounded(user));
    turns.add("Milo: " + bounded(assistant));
    var history = Json.createArrayBuilder();
    for (int i = Math.max(0, turns.size() - 6); i < turns.size(); i++) history.add(turns.get(i));
    session.setContext(Json.createObjectBuilder(root).add("chatHistory", history).build().toString());
    sessions.save(session);
  }

  private List<String> read(ConversationSession session) {
    List<String> turns = new ArrayList<>();
    JsonArray entries = Jsons.array(root(session), "chatHistory");
    for (JsonValue entry : entries) {
      if (entry.getValueType() == JsonValue.ValueType.STRING) turns.add(((jakarta.json.JsonString) entry).getString());
    }
    return turns;
  }
  private JsonObject root(ConversationSession session) {
    String context = session.getContext();
    return context == null || context.isBlank() ? Json.createObjectBuilder().build()
      : Jsons.parseObject(context, "Conversation context");
  }
  private static String bounded(String text) {
    if (text == null) return "";
    return text.length() <= 400 ? text : text.substring(0, 400);
  }
}
