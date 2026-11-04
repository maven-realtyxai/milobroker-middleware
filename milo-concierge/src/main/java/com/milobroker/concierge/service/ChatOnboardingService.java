package com.milobroker.concierge.service;

import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasCity;
import com.milobroker.concierge.dto.OnboardingDtos.AtlasState;
import com.milobroker.concierge.dto.OnboardingDtos.CompletionRequest;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import com.milobroker.concierge.service.InputGuardrailService.Decision;
import com.milobroker.concierge.service.InputGuardrailService.Verdict;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plain-text conversational onboarding slot machine. No WhatsApp Flow is
 * involved: incomplete users advance through OB_ASK_NAME, OB_ASK_ROLE,
 * OB_ASK_STATE, OB_ASK_CITY and OB_ASK_TERMS, persisted in
 * conversation_sessions (state column plus context JSONB holding
 * name, role, stateCode and cityCode). Slot validation reuses
 * OnboardingService and AtlasGeoClient; only an explicit YES (or Y/accept)
 * at OB_ASK_TERMS completes onboarding via OnboardingService.complete.
 */
@Service
public class ChatOnboardingService {
  public static final String STATE_ASK_NAME = "OB_ASK_NAME";
  public static final String STATE_ASK_ROLE = "OB_ASK_ROLE";
  public static final String STATE_ASK_STATE = "OB_ASK_STATE";
  public static final String STATE_ASK_CITY = "OB_ASK_CITY";
  public static final String STATE_ASK_TERMS = "OB_ASK_TERMS";
  public static final String STATE_DONE = "DONE";
  static final String CHANNEL = "whatsapp";
  private static final Logger log = LoggerFactory.getLogger(ChatOnboardingService.class);
  private static final List<String> ORDER = List.of(
    STATE_ASK_NAME, STATE_ASK_ROLE, STATE_ASK_STATE, STATE_ASK_CITY, STATE_ASK_TERMS);
  private static final Map<String, String> SLOT_OF_STATE = Map.of(
    STATE_ASK_NAME, "name",
    STATE_ASK_ROLE, "role",
    STATE_ASK_STATE, "stateCode",
    STATE_ASK_CITY, "cityCode");
  private static final Map<String, String> LABEL_OF_STATE = Map.of(
    STATE_ASK_NAME, "Your name",
    STATE_ASK_ROLE, "Your role",
    STATE_ASK_STATE, "State",
    STATE_ASK_CITY, "City",
    STATE_ASK_TERMS, "Terms acceptance");

  private final OnboardingService onboarding;
  private final AtlasGeoClient atlas;
  private final ConversationSessionRepository sessions;
  private final WhatsAppClient whatsapp;
  private final InputGuardrailService guardrail;

  public ChatOnboardingService(OnboardingService onboarding, AtlasGeoClient atlas,
      ConversationSessionRepository sessions, WhatsAppClient whatsapp,
      InputGuardrailService guardrail) {
    this.onboarding = onboarding;
    this.atlas = atlas;
    this.sessions = sessions;
    this.whatsapp = whatsapp;
    this.guardrail = guardrail;
  }

  /** One inbound user message advances (or re-prompts) the onboarding machine. */
  public void handle(String from, String senderName, String body, String messageType) {
    ProfileDto profile = onboarding.profile(from);
    if (profile.completed()) {
      whatsapp.sendText(from, "Thanks " + displayOf(senderName, profile.displayName())
        + "! Your onboarding is complete. Tell me what you are looking for.");
      return;
    }
    ConversationSession session = load(from);
    Map<String, String> ctx = readContext(session);
    String greet = displayOf(senderName, ctx.get("name"));
    if (InputGuardrailService.isVoiceType(messageType)) {
      whatsapp.sendText(from, "Hi " + greet + "! I can't listen to voice notes yet - "
        + "please type your answer. " + promptFor(session.getState(), ctx, greet));
      return;
    }
    String text = body == null ? "" : body.trim();
    if (text.equalsIgnoreCase("RESTART")) {
      ctx.clear();
      session.setState(STATE_ASK_NAME);
      writeContext(session, ctx);
      sessions.save(session);
      whatsapp.sendText(from, namePrompt(displayOf(senderName, null)));
      return;
    }
    if (text.equalsIgnoreCase("BACK")) {
      goBack(session, ctx);
      writeContext(session, ctx);
      sessions.save(session);
      Map<String, String> after = readContext(session);
      whatsapp.sendText(from, promptFor(session.getState(), after,
        displayOf(senderName, after.get("name"))));
      return;
    }
    if (text.isEmpty()) {
      whatsapp.sendText(from, promptFor(session.getState(), ctx, greet));
      return;
    }
    if (text.equalsIgnoreCase("SKIP")) {
      whatsapp.sendText(from, LABEL_OF_STATE.getOrDefault(session.getState(), "This step")
        + " is required to finish onboarding. " + promptFor(session.getState(), ctx, greet));
      return;
    }
    switch (session.getState()) {
      case STATE_ASK_NAME -> handleName(from, senderName, session, ctx, greet, text);
      case STATE_ASK_ROLE -> handleRole(from, session, ctx, greet, text);
      case STATE_ASK_STATE -> handleState(from, session, ctx, greet, text);
      case STATE_ASK_CITY -> handleCity(from, session, ctx, greet, text);
      case STATE_ASK_TERMS -> handleTerms(from, session, ctx, greet, text);
      default -> {
        session.setState(STATE_ASK_NAME);
        sessions.save(session);
        whatsapp.sendText(from, namePrompt(greet));
      }
    }
  }

  private void handleName(String from, String senderName, ConversationSession session,
      Map<String, String> ctx, String greet, String text) {
    Verdict verdict = guardrail.evaluateText(text);
    if (verdict.decision() == Decision.HARMFUL) {
      whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
      return;
    }
    if (verdict.decision() == Decision.GREETING) {
      whatsapp.sendText(from, namePrompt(greet));
      return;
    }
    String role = parseRole(text);
    if (role != null && senderName != null && !senderName.isBlank()) {
      ctx.put("name", senderName.trim());
      ctx.put("role", role);
      session.setState(STATE_ASK_STATE);
      writeContext(session, ctx);
      sessions.save(session);
      whatsapp.sendText(from, statePrompt());
      return;
    }
    if (verdict.decision() == Decision.OFF_TOPIC
        && (text.length() > 40 || text.split("\\s+").length > 4)) {
      whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity() + " " + namePrompt(greet));
      return;
    }
    ctx.put("name", text);
    session.setState(STATE_ASK_ROLE);
    writeContext(session, ctx);
    sessions.save(session);
    whatsapp.sendText(from, rolePrompt(text));
  }

  private void handleRole(String from, ConversationSession session,
      Map<String, String> ctx, String greet, String text) {
    String role = parseRole(text);
    if (role == null) {
      Verdict verdict = guardrail.evaluateText(text);
      if (verdict.decision() == Decision.HARMFUL) {
        whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
        return;
      }
      if (verdict.decision() == Decision.OFF_TOPIC) {
        whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity() + " "
          + rolePrompt(ctx.get("name")));
        return;
      }
      if (verdict.decision() == Decision.GREETING) {
        whatsapp.sendText(from, "Hi " + greet + "! " + rolePrompt(ctx.get("name")));
        return;
      }
      whatsapp.sendText(from, "I didn't catch that. " + rolePrompt(ctx.get("name")));
      return;
    }
    ctx.put("role", role);
    session.setState(STATE_ASK_STATE);
    writeContext(session, ctx);
    sessions.save(session);
    whatsapp.sendText(from, statePrompt());
  }

  private void handleState(String from, ConversationSession session,
      Map<String, String> ctx, String greet, String text) {
    String code = matchState(text);
    if (code == null) {
      Verdict verdict = guardrail.evaluateText(text);
      if (verdict.decision() == Decision.HARMFUL) {
        whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
        return;
      }
      if (verdict.decision() == Decision.GREETING) {
        whatsapp.sendText(from, "Hi " + greet + "! " + statePrompt());
        return;
      }
      whatsapp.sendText(from, "Hmm, I couldn't find '" + text + "' in our states. " + statePrompt());
      return;
    }
    ctx.put("stateCode", code);
    session.setState(STATE_ASK_CITY);
    writeContext(session, ctx);
    sessions.save(session);
    whatsapp.sendText(from, cityPrompt(code));
  }

  private void handleCity(String from, ConversationSession session,
      Map<String, String> ctx, String greet, String text) {
    String stateCode = ctx.get("stateCode");
    String code = matchCity(stateCode, text);
    if (code == null) {
      Verdict verdict = guardrail.evaluateText(text);
      if (verdict.decision() == Decision.HARMFUL) {
        whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
        return;
      }
      if (verdict.decision() == Decision.GREETING) {
        whatsapp.sendText(from, "Hi " + greet + "! " + cityPrompt(stateCode));
        return;
      }
      whatsapp.sendText(from, "Hmm, '" + text + "' doesn't look like a city in "
        + stateDisplay(stateCode) + ". " + cityPrompt(stateCode));
      return;
    }
    ctx.put("cityCode", code);
    session.setState(STATE_ASK_TERMS);
    writeContext(session, ctx);
    sessions.save(session);
    whatsapp.sendText(from, termsPrompt(ctx.get("name")));
  }

  private void handleTerms(String from, ConversationSession session,
      Map<String, String> ctx, String greet, String text) {
    Verdict verdict = guardrail.evaluateText(text);
    if (verdict.decision() == Decision.HARMFUL) {
      whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
      return;
    }
    if (!isAccept(text)) {
      whatsapp.sendText(from, "Please reply YES to accept the terms and finish onboarding. "
        + termsPrompt(ctx.get("name")));
      return;
    }
    try {
      ProfileDto saved = onboarding.complete(from, new CompletionRequest(
        ctx.get("name"), ctx.get("role"), ctx.get("stateCode"), ctx.get("cityCode"), true));
      session.setState(STATE_DONE);
      writeContext(session, ctx);
      sessions.save(session);
      whatsapp.sendText(from, "Thanks " + saved.displayName()
        + "! Your onboarding is complete. Tell me what you are looking for.");
    } catch (ResponseStatusException ex) {
      log.warn("Chat onboarding save rejected for {}", from, ex);
      whatsapp.sendText(from, "I could not save that: " + ex.getReason()
        + ". Please check your answers. " + termsPrompt(ctx.get("name")));
    } catch (Exception ex) {
      log.warn("Chat onboarding save failed for {}", from, ex);
      whatsapp.sendText(from, "Something went wrong saving your details. "
        + "Please reply YES to try again.");
    }
  }

  /** Accepts rent/rental, buy/purchase/sale, list/post/sell, agent/broker, or 1-4. */
  static String parseRole(String text) {
    String upper = text.trim().toUpperCase(Locale.ROOT);
    String first = upper.split("[\\s).:]+", 2)[0];
    if (first.equals("1")) {
      return "RENTAL";
    }
    if (first.equals("2")) {
      return "BUY";
    }
    if (first.equals("3")) {
      return "LIST";
    }
    if (first.equals("4")) {
      return "AGENT";
    }
    if (upper.equals("RENTAL") || upper.contains("RENT")) {
      return "RENTAL";
    }
    if (upper.equals("BUY") || upper.contains("BUY") || upper.contains("PURCHASE")
        || upper.contains("SALE")) {
      return "BUY";
    }
    if (upper.equals("LIST") || upper.contains("LIST") || upper.contains("POST")
        || upper.contains("SELL")) {
      return "LIST";
    }
    if (upper.equals("AGENT") || upper.contains("AGENT") || upper.contains("BROKER")) {
      return "AGENT";
    }
    return null;
  }

  private String matchState(String text) {
    String norm = text.trim();
    for (AtlasState state : atlas.states()) {
      if (state.code().equalsIgnoreCase(norm) || state.displayName().equalsIgnoreCase(norm)) {
        return state.code().toUpperCase(Locale.ROOT);
      }
    }
    return null;
  }

  private String matchCity(String stateCode, String text) {
    if (stateCode == null) {
      return null;
    }
    String norm = text.trim();
    for (AtlasCity city : atlas.cities(stateCode)) {
      if (city.code().equalsIgnoreCase(norm) || city.displayName().equalsIgnoreCase(norm)) {
        return city.code().toUpperCase(Locale.ROOT);
      }
    }
    return null;
  }

  private static boolean isAccept(String text) {
    String upper = text.trim().toUpperCase(Locale.ROOT);
    return upper.equals("YES") || upper.equals("Y") || upper.equals("ACCEPT")
      || upper.equals("I ACCEPT") || upper.startsWith("YES ");
  }

  private void goBack(ConversationSession session, Map<String, String> ctx) {
    int at = ORDER.indexOf(session.getState());
    if (at <= 0) {
      session.setState(STATE_ASK_NAME);
      return;
    }
    String prev = ORDER.get(at - 1);
    session.setState(prev);
    for (int i = at - 1; i < ORDER.size(); i++) {
      ctx.remove(SLOT_OF_STATE.get(ORDER.get(i)));
    }
  }

  private ConversationSession load(String from) {
    Optional<ConversationSession> existing =
      sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc(CHANNEL, from);
    if (existing.isPresent() && ORDER.contains(existing.get().getState())) {
      return existing.get();
    }
    ConversationSession fresh = new ConversationSession();
    fresh.setChannel(CHANNEL);
    fresh.setChannelUserId(from);
    fresh.setState(STATE_ASK_NAME);
    fresh.setContext("{}");
    return sessions.save(fresh);
  }

  private static Map<String, String> readContext(ConversationSession session) {
    Map<String, String> out = new HashMap<>();
    String raw = session.getContext();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    try {
      JsonObject obj = Jsons.parseObject(raw, "Onboarding context");
      for (String key : List.of("name", "role", "stateCode", "cityCode")) {
        String value = Jsons.string(obj, key);
        if (value != null) {
          out.put(key, value);
        }
      }
    } catch (ResponseStatusException ex) {
      log.warn("Ignoring unreadable onboarding context", ex);
    }
    return out;
  }

  private static void writeContext(ConversationSession session, Map<String, String> ctx) {
    JsonObjectBuilder builder = Json.createObjectBuilder();
    for (Map.Entry<String, String> entry : ctx.entrySet()) {
      if (entry.getValue() != null) {
        builder.add(entry.getKey(), entry.getValue());
      }
    }
    session.setContext(builder.build().toString());
  }

  private static String displayOf(String senderName, String stored) {
    if (senderName != null && !senderName.isBlank()) {
      return senderName.trim();
    }
    if (stored != null && !stored.isBlank()) {
      return stored.trim();
    }
    return "there";
  }

  private String promptFor(String state, Map<String, String> ctx, String greet) {
    return switch (state) {
      case STATE_ASK_ROLE -> rolePrompt(ctx.get("name"));
      case STATE_ASK_STATE -> statePrompt();
      case STATE_ASK_CITY -> cityPrompt(ctx.get("stateCode"));
      case STATE_ASK_TERMS -> termsPrompt(ctx.get("name"));
      default -> namePrompt(greet);
    };
  }

  private static String namePrompt(String greet) {
    return "Hi " + greet + "! Welcome to MiloBroker. What's your full name?";
  }

  private static String rolePrompt(String name) {
    String who = name == null || name.isBlank() ? "you" : name.trim();
    return "Thanks " + who + "! What brings you to MiloBroker? "
      + "Reply with 1) Rent, 2) Buy, 3) List a property, or 4) Agent.";
  }

  private String statePrompt() {
    List<String> options = new ArrayList<>();
    for (AtlasState state : atlas.states()) {
      options.add(state.displayName() + " (" + state.code() + ")");
    }
    if (options.isEmpty()) {
      return "Which state are you looking in? Just reply with the state name or code.";
    }
    return "Which state are you looking in? Options: " + String.join(", ", options)
      + ". Just reply with the code or name.";
  }

  private String cityPrompt(String stateCode) {
    String where = stateDisplay(stateCode);
    List<String> options = new ArrayList<>();
    if (stateCode != null) {
      for (AtlasCity city : atlas.cities(stateCode)) {
        options.add(city.displayName() + " (" + city.code() + ")");
      }
    }
    if (options.isEmpty()) {
      return "Which city in " + where + "? Just reply with the city name or code.";
    }
    return "Which city in " + where + "? Options: " + String.join(", ", options)
      + ". Just reply with the code or name.";
  }

  private static String termsPrompt(String name) {
    String who = name == null || name.isBlank() ? "you" : name.trim();
    return "Almost done, " + who + "! Reply YES to accept the MiloBroker terms "
      + "and finish onboarding.";
  }

  private String stateDisplay(String stateCode) {
    if (stateCode != null) {
      for (AtlasState state : atlas.states()) {
        if (state.code().equalsIgnoreCase(stateCode)) {
          return state.displayName();
        }
      }
    }
    return stateCode == null ? "your state" : stateCode;
  }
}
