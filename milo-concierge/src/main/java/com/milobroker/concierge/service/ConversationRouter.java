package com.milobroker.concierge.service;

import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Free-text replies: property keyword or model relevance, then incomplete users
 * go to the plain-text onboarding machine (never the model). Completed users
 * take the POST branch (new property drafts), the SEARCH branch (marketplace
 * search as a plain-text numbered list), or plain chat through the model and
 * the output guardrail. No interactive cards anywhere.
 */
@Service
public class ConversationRouter {
  private static final Logger log = LoggerFactory.getLogger(ConversationRouter.class);

  private static final Pattern POST_INTENT =
    Pattern.compile("(?i)\\b(list|listing|sell|rent\\s+out|post)\\b");
  private static final Pattern LOCALITY_AFTER_IN =
    Pattern.compile("(?i)\\bin\\s+([a-z][a-z .'\\-]{1,30}?)(?=\\s+(?:under|below|with|for|budget|near)\\b|[,!?]|$)");
  private static final Pattern LOCALITY_AND_CITY =
    Pattern.compile("(?i)^\\s*([a-z][a-z .'-]{1,40})\\s*,\\s*([a-z][a-z .'-]{1,30})\\s*$");
  private static final Pattern BEDROOMS =
    Pattern.compile("(?i)(\\d+)\\s*(?:bhk|bed(?:room)?s?)");
  private static final Pattern BUDGET =
    Pattern.compile("(?i)(?:under|below|max|up ?to|within)\\s*[₹$]?\\s*(\\d[\\d,]*)");

  private static final Set<String> SEARCH_TERMS = Set.of(
    "rent", "buy", "bhk", "bedroom", "search", "find", "looking",
    "budget", "price", "flat", "apartment", "house", "villa", "plot",
    "room", "furnish", "locality", "location", "move", "shift",
    "possession", "visit", "site");
  private static final Pattern PROPERTY_TERMS = Pattern.compile(
    "(?iu)\\b(?:property|properties|rent|rental|renting|buy|buyer|purchase|sell|seller|sale|post|listing|list|"
      + "flat|apartment|house|home|villa|plot|pg|room|bedrooms?|bhk|budget|locality|tenant|landlord|owner|"
      + "agent|broker|lease|deposit|onboard|register|signup|agreement)\\b|\\b\\d+\\s*bhk\\b");
  private static final Set<String> GREETINGS = Set.of("hi", "hello", "hey", "namaste", "namaskar");

  private final OnboardingService onboarding;
  private final WhatsAppClient whatsapp;
  private final ModelClient model;
  private final ChatOnboardingService chat;
  private final PostPropertyFlowService postFlow;
  private final OutputGuardrailService outputGuardrail;
  private final RestClient search;
  private ConversationMemory memory;

  @Autowired
  public void setConversationMemory(ConversationMemory memory) { this.memory = memory; }

  /** Legacy wiring: onboarding gate only, no post/search/output stages. */
  public ConversationRouter(OnboardingService onboarding, WhatsAppClient whatsapp,
      InputGuardrailService guardrail, ModelClient model) {
    this(onboarding, whatsapp, guardrail, model, null, null, null, null, null);
  }

  @Autowired
  public ConversationRouter(OnboardingService onboarding, WhatsAppClient whatsapp,
      InputGuardrailService guardrail, ModelClient model,
      ChatOnboardingService chat, PostPropertyFlowService postFlow,
      OutputGuardrailService outputGuardrail, RestClient.Builder restBuilder,
      @Value("${milobroker.marketplace.base-url:${milobroker.downstream.marketplace-base-url:http://localhost:8082}}")
      String marketplaceBaseUrl) {
    this.onboarding = onboarding;
    this.whatsapp = whatsapp;
    this.model = model;
    this.chat = chat;
    this.postFlow = postFlow;
    this.outputGuardrail = outputGuardrail;
    this.search = restBuilder == null || marketplaceBaseUrl == null
      ? null : restBuilder.baseUrl(marketplaceBaseUrl).build();
  }

  public void reply(String from, String senderName, String text, String messageType) {
    String body = text == null ? "" : text;
    ProfileDto profile = onboarding.profile(from);
    String name = senderName != null && !senderName.isBlank() ? senderName : profile.displayName();
    String greet = name != null && !name.isBlank() ? name : "there";
    if (profile.completed() && postFlow != null && postFlow.isInProgress(from)) {
      postFlow.handle(from, senderName, body, messageType);
      return;
    }
    if (InputGuardrailService.isVoiceType(messageType)) {
      whatsapp.sendText(from, "Hi " + greet + "! I can't listen to voice notes yet - "
        + "please type your message and I'll reply.");
      return;
    }
    if (!profile.completed()) {
      if (chat != null) {
        chat.handle(from, senderName, body, messageType);
      }
      return;
    }
    if (!propertyRelevant(from, body)) {
      whatsapp.sendText(from, "Hi " + greet + "! I can help with properties, rentals, purchases and listings. "
        + "Please tell me what you are looking for.");
      return;
    }
    if (postFlow != null && (postFlow.isInProgress(from) || isPostIntent(body))) {
      postFlow.handle(from, senderName, body, messageType);
      return;
    }
    rememberLocation(from, profile, body);
    if (memory != null && search != null) {
      try {
        String locality = localityOf(body);
        if (locality == null) locality = memory.locality(from);
        String pending = memory.pendingSearch(from);
        Matcher locationReply = LOCALITY_AND_CITY.matcher(body);
        if (locality != null && pending != null && locationReply.matches()
            && profile.cityCode() != null && locationReply.group(2).trim().equalsIgnoreCase(profile.cityCode())) {
          if (searchAndReply(from, greet, profile, pending)) {
            memory.savePendingSearch(from, null);
            return;
          }
        }
        if (locality == null && looksLikeSearch(body)) {
          memory.savePendingSearch(from, body);
          whatsapp.sendText(from, "Which locality and city? Reply like: Devanahalli, BLR.");
          return;
        }
      } catch (Exception ex) {
        log.warn("Could not update search context", ex);
      }
    }
    if (search != null && searchAndReply(from, greet, profile, body)) {
      return;
    }
    if (!model.isConfigured()) {
      sendChatFallback(from, greet, body);
      return;
    }
    try {
      String history = history(from);
      String raw = model.chat(context(profile, greet, body, history));
      String safe = outputGuardrail == null ? raw : outputGuardrail.sanitize(raw, greet);
      String reply = safe != null ? safe : fallback(greet, body);
      whatsapp.sendText(from, reply);
      remember(from, body, reply);
    } catch (Exception ex) {
      log.warn("Model reply failed for {}", from, ex);
      sendChatFallback(from, greet, body);
    }
  }

  static boolean isPostIntent(String text) {
    return text != null && POST_INTENT.matcher(text).find();
  }

  private boolean propertyRelevant(String from, String body) {
    String trimmed = body.trim();
    if (trimmed.isEmpty() || GREETINGS.contains(trimmed.toLowerCase(Locale.ROOT))
        || PROPERTY_TERMS.matcher(trimmed).find()) return true;
    if (!model.isConfigured()) return true;
    try {
      // UNKNOWN is allowed through so a temporary model failure cannot reject a real enquiry.
      return model.classifyProperty(trimmed, history(from)) != ModelClient.Relevance.OTHER;
    } catch (Exception ex) {
      log.warn("Property relevance check unavailable; continuing with message");
      return true;
    }
  }

  /**
   * Best-effort marketplace search. Returns true when a numbered list was
   * sent; false (unknown locality, empty results, any failure) lets the
   * caller fall through to the model.
   */
  private boolean searchAndReply(String from, String greet, ProfileDto profile, String body) {
    try {
      if (!looksLikeSearch(body) || profile.cityCode() == null || profile.cityCode().isBlank()) {
        return false;
      }
      String locality = localityOf(body);
      if (locality == null && memory != null) locality = memory.locality(from);
      if (locality == null) {
        return false;
      }
      String listingType = Pattern.compile("(?i)\\b(buy|purchase|sale)\\b").matcher(body).find() ? "SALE"
        : Pattern.compile("(?i)\\b(rent|rental)\\b").matcher(body).find() ? "RENT"
        : "BUY".equals(profile.intent()) ? "SALE" : "RENT";
      Integer bedrooms = bedroomsOf(body);
      Long budget = budgetOf(body);
      JsonObject request = Json.createObjectBuilder()
        .add("cityCode", profile.cityCode().toUpperCase(Locale.ROOT))
        .add("localityCode", locality)
        .add("listingType", listingType)
        .build();
      jakarta.json.JsonObjectBuilder enriched = Json.createObjectBuilder(request);
      if (bedrooms != null) {
        enriched.add("bedrooms", bedrooms);
      }
      if (budget != null) {
        enriched.add("maximumBudgetInr", budget);
      }
      String json = search.post().uri("/api/v1/search")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(enriched.build().toString())
        .retrieve().body(String.class);
      List<JsonObject> results = resultsOf(json);
      if (results.isEmpty()) {
        String place = locality.isEmpty() ? "that area"
          : locality.substring(0, 1).toUpperCase(Locale.ROOT)
            + locality.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        whatsapp.sendText(from, "Hi " + greet + "! I couldn't find a match for those filters in "
          + place + ". Try another locality or change your requirements.");
        return true;
      }
      StringBuilder out = new StringBuilder("Hi " + greet
        + "! Here are some options:");
      int shown = 0;
      for (JsonObject row : results) {
        if (shown >= 5) {
          break;
        }
        shown++;
        out.append('\n').append(shown).append(". ")
          .append(Jsons.string(row, "title"));
        String price = Jsons.string(row, "priceInr");
        String loc = Jsons.string(row, "localityCode");
        out.append(" - Rs.").append(price == null ? "?" : price);
        if (loc != null) {
          out.append(" (").append(loc).append(')');
        }
      }
      whatsapp.sendText(from, out.toString());
      return true;
    } catch (Exception ex) {
      log.warn("Marketplace search fell through to model for {}", from, ex);
      return false;
    }
  }

  private static boolean looksLikeSearch(String body) {
    String norm = body.toLowerCase(Locale.ROOT);
    return SEARCH_TERMS.stream().anyMatch(norm::contains);
  }

  private static String localityOf(String body) {
    Matcher matcher = LOCALITY_AFTER_IN.matcher(body);
    if (!matcher.find()) {
      return null;
    }
    String locality = matcher.group(1).trim()
      .toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    return locality.isEmpty() ? null : locality;
  }

  private static Integer bedroomsOf(String body) {
    Matcher matcher = BEDROOMS.matcher(body);
    if (!matcher.find()) {
      return null;
    }
    try {
      int value = Integer.parseInt(matcher.group(1));
      return value >= 0 && value <= 20 ? value : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static Long budgetOf(String body) {
    Matcher matcher = BUDGET.matcher(body);
    if (!matcher.find()) {
      return null;
    }
    try {
      long value = Long.parseLong(matcher.group(1).replace(",", ""));
      return value > 0 ? value : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static List<JsonObject> resultsOf(String json) {
    List<JsonObject> out = new ArrayList<>();
    JsonObject root = Jsons.parseObject(json, "Marketplace search");
    JsonArray results = Jsons.array(root, "results");
    for (JsonValue value : results) {
      if (value instanceof JsonObject row) {
        out.add(row);
      }
    }
    return out;
  }

  private String context(ProfileDto profile, String greet, String text, String history) {
    return "Profile: name=" + greet + ", intent=" + profile.intent()
      + ", state=" + profile.stateCode() + ", city=" + profile.cityCode()
      + ", onboarding complete. Recent conversation:\n" + history + "Current message: " + text;
  }

  private String fallback(String greet, String text) {
    if (text != null && text.matches("(?i)^[a-z][a-z .'-]+\\s*,\\s*[a-z][a-z .'-]+$")) {
      return "Thanks! Are you looking to rent or buy there? Please include your budget and bedrooms.";
    }
    return "Hi " + greet + "! I'm catching up. To search, try '2 BHK rent in Koramangala'. "
      + "To advertise, say 'post a property'.";
  }
  private String history(String from) {
    try { return memory == null ? "" : memory.history(from); }
    catch (Exception ex) { log.warn("Could not load conversation history", ex); return ""; }
  }
  private void remember(String from, String body, String reply) {
    try { if (memory != null) memory.record(from, body, reply); }
    catch (Exception ex) { log.warn("Could not save conversation history", ex); }
  }
  private void sendChatFallback(String from, String greet, String body) {
    String reply = fallback(greet, body);
    whatsapp.sendText(from, reply);
    remember(from, body, reply);
  }
  private void rememberLocation(String from, ProfileDto profile, String body) {
    if (memory == null || body == null) return;
    String locality = localityOf(body);
    if (locality == null) {
      Matcher pair = LOCALITY_AND_CITY.matcher(body);
      if (pair.matches() && profile.cityCode() != null
          && pair.group(2).trim().equalsIgnoreCase(profile.cityCode())) {
        locality = pair.group(1).trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
      }
    }
    if (locality != null) {
      try { memory.rememberLocality(from, locality); }
      catch (Exception ex) { log.warn("Could not save search locality", ex); }
    }
  }
}
