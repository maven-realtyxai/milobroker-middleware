package com.milobroker.concierge.service;

import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.InputGuardrailService.Decision;
import com.milobroker.concierge.service.InputGuardrailService.Verdict;
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
 * Free-text replies: input guardrail first, then voice, then incomplete users
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
    Pattern.compile("(?i)\\bin\\s+([a-z][a-z .'\\-]{1,30})");
  private static final Pattern BEDROOMS =
    Pattern.compile("(?i)(\\d+)\\s*(?:bhk|bed(?:room)?s?)");
  private static final Pattern BUDGET =
    Pattern.compile("(?i)(?:under|below|max|up ?to|within)\\s*[₹$]?\\s*(\\d[\\d,]*)");

  private static final Set<String> SEARCH_TERMS = Set.of(
    "rent", "buy", "bhk", "bedroom", "search", "find", "looking",
    "budget", "price", "flat", "apartment", "house", "villa", "plot",
    "room", "furnish", "locality", "location", "move", "shift",
    "possession", "visit", "site");

  private final OnboardingService onboarding;
  private final WhatsAppClient whatsapp;
  private final InputGuardrailService guardrail;
  private final ModelClient model;
  private final ChatOnboardingService chat;
  private final PostPropertyFlowService postFlow;
  private final OutputGuardrailService outputGuardrail;
  private final RestClient search;

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
    this.guardrail = guardrail;
    this.model = model;
    this.chat = chat;
    this.postFlow = postFlow;
    this.outputGuardrail = outputGuardrail;
    this.search = restBuilder == null || marketplaceBaseUrl == null
      ? null : restBuilder.baseUrl(marketplaceBaseUrl).build();
  }

  public void reply(String from, String senderName, String text, String messageType) {
    Verdict verdict = guardrail.evaluate(messageType, text);
    String body = text == null ? "" : text;
    ProfileDto profile = onboarding.profile(from);
    String name = senderName != null && !senderName.isBlank() ? senderName : profile.displayName();
    String greet = name != null && !name.isBlank() ? name : "there";
    if (profile.completed() && postFlow != null && postFlow.isInProgress(from)) {
      postFlow.handle(from, senderName, body, messageType);
      return;
    }
    if (!verdict.allowed()) {
      whatsapp.sendText(from, "Hi " + greet + "! " + verdict.clarity());
      return;
    }
    if (verdict.decision() == Decision.VOICE) {
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
    if (postFlow != null && (postFlow.isInProgress(from) || isPostIntent(body))) {
      postFlow.handle(from, senderName, body, messageType);
      return;
    }
    if (search != null && searchAndReply(from, greet, profile, body)) {
      return;
    }
    if (!model.isConfigured()) {
      whatsapp.sendText(from, fallback(greet));
      return;
    }
    try {
      String raw = model.chat(context(profile, greet, body));
      String safe = outputGuardrail == null ? raw : outputGuardrail.sanitize(raw, greet);
      whatsapp.sendText(from, safe != null ? safe : fallback(greet));
    } catch (Exception ex) {
      log.warn("Model reply failed for {}", from, ex);
      whatsapp.sendText(from, fallback(greet));
    }
  }

  static boolean isPostIntent(String text) {
    return text != null && POST_INTENT.matcher(text).find();
  }

  /**
   * Best-effort marketplace search. Returns true when a numbered list was
   * sent; false (unknown locality, empty results, any failure) lets the
   * caller fall through to the model.
   */
  private boolean searchAndReply(String from, String greet, ProfileDto profile, String body) {
    try {
      String norm = body.toLowerCase(Locale.ROOT);
      boolean looksLikeSearch = false;
      for (String term : SEARCH_TERMS) {
        if (norm.contains(term)) {
          looksLikeSearch = true;
          break;
        }
      }
      if (!looksLikeSearch || profile.cityCode() == null || profile.cityCode().isBlank()) {
        return false;
      }
      String locality = localityOf(body);
      if (locality == null) {
        return false;
      }
      String listingType = "BUY".equals(profile.intent()) ? "SALE" : "RENT";
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
        whatsapp.sendText(from, "Hi " + greet + "! There are currently no properties listed in "
          + place + ". Reply REFINE with a different locality or budget and I'll look again.");
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

  private String context(ProfileDto profile, String greet, String text) {
    return "Profile: name=" + greet + ", intent=" + profile.intent()
      + ", state=" + profile.stateCode() + ", city=" + profile.cityCode()
      + ", onboarding complete. Message: " + text;
  }

  private String fallback(String greet) {
    return "Hi " + greet + "! Thanks for your message - I'm catching up. Meanwhile, tell me "
      + "your city, budget, and bedroom needs, or open the onboarding form to complete your profile.";
  }
}
