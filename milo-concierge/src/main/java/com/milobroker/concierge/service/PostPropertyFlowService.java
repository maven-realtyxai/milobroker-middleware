package com.milobroker.concierge.service;

import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.json.Jsons;
import com.milobroker.concierge.service.InputGuardrailService.Decision;
import com.milobroker.concierge.service.InputGuardrailService.Verdict;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
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
 * Plain-text slot machine for posting a property. No interactive cards:
 * listingType (RENT/SALE) -&gt; propertyType -&gt; cityCode/localityCode
 * -&gt; bedrooms 0-20 -&gt; priceInr positive -&gt; title -&gt; description.
 * The draft lives in the session context under {@code postDraft.*}. The
 * 5-active-listings pre-check fails fast before the first question.
 */
@Service
public class PostPropertyFlowService {
  static final String STATE_LISTING_TYPE = "POST_LISTING_TYPE";
  static final String STATE_PROPERTY_TYPE = "POST_PROPERTY_TYPE";
  static final String STATE_LOCATION = "POST_LOCATION";
  static final String STATE_BEDROOMS = "POST_BEDROOMS";
  static final String STATE_PRICE = "POST_PRICE";
  static final String STATE_TITLE = "POST_TITLE";
  static final String STATE_DESCRIPTION = "POST_DESCRIPTION";
  static final String STATE_DONE = "DONE";
  static final String CHANNEL = "whatsapp";

  static final String LIMIT_MESSAGE =
    "You already have 5 active listings. Close one before posting a new property.";

  private static final List<String> ORDER = List.of(
    STATE_LISTING_TYPE, STATE_PROPERTY_TYPE, STATE_LOCATION,
    STATE_BEDROOMS, STATE_PRICE, STATE_TITLE, STATE_DESCRIPTION);

  private static final Logger log = LoggerFactory.getLogger(PostPropertyFlowService.class);

  private final com.milobroker.concierge.repository.ConversationSessionRepository sessions;
  private final WhatsAppClient whatsapp;
  private final InputGuardrailService guardrail;
  private final MarketplaceListingsClient listings;

  public PostPropertyFlowService(
      com.milobroker.concierge.repository.ConversationSessionRepository sessions,
      WhatsAppClient whatsapp,
      InputGuardrailService guardrail,
      MarketplaceListingsClient listings) {
    this.sessions = sessions;
    this.whatsapp = whatsapp;
    this.guardrail = guardrail;
    this.listings = listings;
  }

  /** True while the user has an unfinished post draft. */
  public boolean isInProgress(String from) {
    Optional<ConversationSession> existing =
      sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc(CHANNEL, from);
    return existing.isPresent()
      && existing.get().getState() != null
      && existing.get().getState().startsWith("POST_");
  }

  /** Advances (or starts) the post flow for one inbound message. */
  public void handle(String from, String senderName, String text, String messageType) {
    Optional<ConversationSession> existing =
      sessions.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc(CHANNEL, from);
    if (existing.isEmpty() || !ORDER.contains(existing.get().getState())) {
      ConversationSession session = existing.orElseGet(() -> {
        ConversationSession fresh = new ConversationSession();
        fresh.setChannel(CHANNEL);
        fresh.setChannelUserId(from);
        fresh.setContext("{}");
        return fresh;
      });
      Map<String, String> draft = new HashMap<>();
      session.setState(STATE_LISTING_TYPE);
      writeDraft(session, draft);
      sessions.save(session);
      if (!preCheck(from, session, draft)) {
        return;
      }
      whatsapp.sendText(from, listingTypePrompt());
      return;
    }
    ConversationSession session = existing.get();
    Map<String, String> draft = readDraft(session);
    String raw = text == null ? "" : text.trim();
    if (raw.equalsIgnoreCase("RESTART")) {
      draft.clear();
      session.setState(STATE_LISTING_TYPE);
      writeDraft(session, draft);
      sessions.save(session);
      if (!preCheck(from, session, draft)) {
        return;
      }
      whatsapp.sendText(from, listingTypePrompt());
      return;
    }
    if (raw.equalsIgnoreCase("BACK")) {
      goBack(session, draft);
      writeDraft(session, draft);
      sessions.save(session);
      whatsapp.sendText(from, promptFor(session.getState(), draft));
      return;
    }
    if (raw.isEmpty()) {
      whatsapp.sendText(from, promptFor(session.getState(), draft));
      return;
    }
    Verdict verdict = guardrail.evaluateForSlot(slotOf(session.getState()), messageType, raw);
    if (verdict.decision() == Decision.HARMFUL) {
      whatsapp.sendText(from, verdict.clarity() + " " + promptFor(session.getState(), draft));
      return;
    }
    if (verdict.decision() == Decision.VOICE) {
      whatsapp.sendText(from, "I can't listen to voice notes yet - please type your answer. "
        + promptFor(session.getState(), draft));
      return;
    }
    switch (session.getState()) {
      case STATE_LISTING_TYPE -> handleListingType(from, session, draft, raw);
      case STATE_PROPERTY_TYPE -> handlePropertyType(from, session, draft, raw);
      case STATE_LOCATION -> handleLocation(from, session, draft, raw);
      case STATE_BEDROOMS -> handleBedrooms(from, session, draft, raw);
      case STATE_PRICE -> handlePrice(from, session, draft, raw);
      case STATE_TITLE -> handleTitle(from, session, draft, raw);
      case STATE_DESCRIPTION -> handleDescription(from, session, draft, raw);
      default -> {
        session.setState(STATE_LISTING_TYPE);
        sessions.save(session);
        whatsapp.sendText(from, listingTypePrompt());
      }
    }
  }

  /** ACTIVE count gate; returns false and notifies when the user is at the limit. */
  private boolean preCheck(String from, ConversationSession session, Map<String, String> draft) {
    int active;
    try {
      active = listings.activeCount(from);
    } catch (Exception ex) {
      log.warn("Post pre-check failed for {}", from, ex);
      return true;
    }
    if (active >= 5) {
      draft.clear();
      session.setState(STATE_DONE);
      writeDraft(session, draft);
      sessions.save(session);
      whatsapp.sendText(from, LIMIT_MESSAGE);
      return false;
    }
    return true;
  }

  private void handleListingType(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    String upper = raw.toUpperCase(Locale.ROOT);
    String value = null;
    if (upper.contains("RENT")) {
      value = "RENT";
    } else if (upper.contains("SALE") || upper.contains("SELL")) {
      value = "SALE";
    }
    if (value == null) {
      whatsapp.sendText(from, "Please reply RENT or SALE. " + listingTypePrompt());
      return;
    }
    draft.put("listingType", value);
    session.setState(STATE_PROPERTY_TYPE);
    writeDraft(session, draft);
    sessions.save(session);
    whatsapp.sendText(from, propertyTypePrompt(value));
  }

  private void handlePropertyType(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    String value = parsePropertyType(raw);
    if (value == null) {
      whatsapp.sendText(from, "I didn't catch that kind. " + propertyTypePrompt(draft.get("listingType")));
      return;
    }
    draft.put("propertyType", value);
    session.setState(STATE_LOCATION);
    writeDraft(session, draft);
    sessions.save(session);
    whatsapp.sendText(from, locationPrompt());
  }

  private void handleLocation(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    if (!draft.containsKey("cityCode")) {
      String[] parts = raw.split(",", 2);
      String city = parts[0].trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
      if (city.isEmpty()) {
        whatsapp.sendText(from, locationPrompt());
        return;
      }
      draft.put("cityCode", city);
      if (parts.length > 1 && !parts[1].trim().isEmpty()) {
        draft.put("localityCode",
          parts[1].trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_"));
      } else {
        writeDraft(session, draft);
        sessions.save(session);
        whatsapp.sendText(from, "Which locality in " + parts[0].trim()
          + "? Just reply with the locality name.");
        return;
      }
    } else {
      String locality = raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
      if (locality.isEmpty()) {
        whatsapp.sendText(from, "Which locality? Just reply with the locality name.");
        return;
      }
      draft.put("localityCode", locality);
    }
    session.setState(STATE_BEDROOMS);
    writeDraft(session, draft);
    sessions.save(session);
    whatsapp.sendText(from, bedroomsPrompt());
  }

  private void handleBedrooms(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    Integer bedrooms = parseBedrooms(raw);
    if (bedrooms == null) {
      whatsapp.sendText(from, "Please reply with bedrooms as a number 0-20. " + bedroomsPrompt());
      return;
    }
    draft.put("bedrooms", String.valueOf(bedrooms));
    session.setState(STATE_PRICE);
    writeDraft(session, draft);
    sessions.save(session);
    whatsapp.sendText(from, pricePrompt());
  }

  private void handlePrice(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    Long price = parsePrice(raw);
    if (price == null) {
      whatsapp.sendText(from, "That price doesn't look right. " + pricePrompt());
      return;
    }
    draft.put("priceInr", String.valueOf(price));
    session.setState(STATE_TITLE);
    writeDraft(session, draft);
    sessions.save(session);
    whatsapp.sendText(from, titlePrompt());
  }

  private void handleTitle(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    if (raw.length() < 3) {
      whatsapp.sendText(from, "Please give a slightly longer title. " + titlePrompt());
      return;
    }
    draft.put("title", raw);
    session.setState(STATE_DESCRIPTION);
    writeDraft(session, draft);
    sessions.save(session);
    whatsapp.sendText(from, descriptionPrompt());
  }

  private void handleDescription(String from, ConversationSession session,
      Map<String, String> draft, String raw) {
    if (raw.length() < 3) {
      whatsapp.sendText(from, "Please add a slightly longer description. " + descriptionPrompt());
      return;
    }
    draft.put("description", raw);
    try {
      String id = listings.createListing(from, draft.get("title"), draft.get("description"),
        draft.get("listingType"), draft.get("propertyType"), draft.get("cityCode"),
        draft.get("localityCode"), Integer.parseInt(draft.get("bedrooms")),
        Long.parseLong(draft.get("priceInr")));
      draft.clear();
      session.setState(STATE_DONE);
      writeDraft(session, draft);
      sessions.save(session);
      whatsapp.sendText(from, "Posted! ref " + id.substring(0, Math.min(6, id.length())) + ".");
    } catch (ResponseStatusException ex) {
      log.warn("Post create rejected for {}", from, ex);
      whatsapp.sendText(from, "I could not post that: " + ex.getReason()
        + ". Please check your answers and reply to try again.");
    } catch (Exception ex) {
      log.warn("Post create failed for {}", from, ex);
      whatsapp.sendText(from, "Something went wrong posting your property. "
        + "Please reply to try again.");
    }
  }

  static String parsePropertyType(String raw) {
    String upper = raw.trim().toUpperCase(Locale.ROOT);
    if (upper.contains("APARTMENT") || upper.contains("FLAT")) {
      return "APARTMENT";
    }
    if (upper.contains("HOUSE")) {
      return "HOUSE";
    }
    if (upper.contains("VILLA")) {
      return "VILLA";
    }
    if (upper.equals("PG") || upper.contains(" PG") || upper.startsWith("PG ")) {
      return "PG";
    }
    if (upper.contains("PLOT")) {
      return "PLOT";
    }
    if (upper.contains("COMMERCIAL") || upper.contains("SHOP") || upper.contains("OFFICE")) {
      return "COMMERCIAL";
    }
    if (upper.contains("OTHER")) {
      return "OTHER";
    }
    return null;
  }

  static Integer parseBedrooms(String raw) {
    java.util.regex.Matcher matcher =
      java.util.regex.Pattern.compile("(\\d+)").matcher(raw.trim());
    if (!matcher.find()) {
      return null;
    }
    try {
      int value = Integer.parseInt(matcher.group(1));
      if (value < 0 || value > 20) {
        return null;
      }
      return value;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  static Long parsePrice(String raw) {
    String norm = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[₹$,\\s]", "");
    long multiplier = 1;
    if (norm.endsWith("lakh") || norm.endsWith("lac")) {
      multiplier = 100_000;
      norm = norm.replaceAll("(lakh|lac)$", "");
    } else if (norm.endsWith("crore") || norm.endsWith("cr")) {
      multiplier = 10_000_000;
      norm = norm.replaceAll("(crore|cr)$", "");
    } else if (norm.endsWith("k")) {
      multiplier = 1_000;
      norm = norm.substring(0, norm.length() - 1);
    }
    if (!norm.matches("\\d+")) {
      return null;
    }
    try {
      long value = Long.parseLong(norm) * multiplier;
      return value > 0 ? value : null;
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private void goBack(ConversationSession session, Map<String, String> draft) {
    int at = ORDER.indexOf(session.getState());
    if (at <= 0) {
      session.setState(STATE_LISTING_TYPE);
      return;
    }
    String prev = ORDER.get(at - 1);
    session.setState(prev);
    switch (prev) {
      case STATE_LISTING_TYPE -> draft.remove("listingType");
      case STATE_PROPERTY_TYPE -> draft.remove("propertyType");
      case STATE_LOCATION -> { draft.remove("cityCode"); draft.remove("localityCode"); }
      case STATE_BEDROOMS -> draft.remove("bedrooms");
      case STATE_PRICE -> draft.remove("priceInr");
      case STATE_TITLE -> draft.remove("title");
      default -> { }
    }
  }

  private static String slotOf(String state) {
    return switch (state) {
      case STATE_LISTING_TYPE -> "listingType";
      case STATE_PROPERTY_TYPE -> "propertyType";
      case STATE_LOCATION -> "cityCode";
      case STATE_BEDROOMS -> "bedrooms";
      case STATE_PRICE -> "priceInr";
      case STATE_TITLE -> "title";
      case STATE_DESCRIPTION -> "description";
      default -> "post";
    };
  }

  static Map<String, String> readDraft(ConversationSession session) {
    Map<String, String> out = new HashMap<>();
    String raw = session.getContext();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    try {
      JsonObject root = Jsons.parseObject(raw, "Post draft");
      JsonObject draft = Jsons.object(root, "postDraft");
      if (draft == null) {
        return out;
      }
      for (String key : List.of("listingType", "propertyType", "cityCode", "localityCode",
          "bedrooms", "priceInr", "title", "description")) {
        String value = Jsons.string(draft, key);
        if (value != null) {
          out.put(key, value);
        }
      }
    } catch (ResponseStatusException ex) {
      log.warn("Ignoring unreadable post draft", ex);
    }
    return out;
  }

  private static void writeDraft(ConversationSession session, Map<String, String> draft) {
    JsonObjectBuilder builder = Json.createObjectBuilder();
    for (Map.Entry<String, String> entry : draft.entrySet()) {
      if (entry.getValue() != null) {
        builder.add(entry.getKey(), entry.getValue());
      }
    }
    JsonObject root;
    String raw = session.getContext();
    try {
      root = raw == null || raw.isBlank() ? null : Jsons.parseObject(raw, "Post draft");
    } catch (ResponseStatusException ex) {
      root = null;
    }
    JsonObjectBuilder rootBuilder = Json.createObjectBuilder();
    if (root != null) {
      for (String key : root.keySet()) {
        if (!key.equals("postDraft")) {
          rootBuilder.add(key, root.get(key));
        }
      }
    }
    rootBuilder.add("postDraft", builder);
    session.setContext(rootBuilder.build().toString());
  }

  private static String listingTypePrompt() {
    return "Let's post your property. Is it for RENT or SALE? Just reply with one word.";
  }

  private static String propertyTypePrompt(String listingType) {
    return "Got it - " + (listingType == null ? "noted" : listingType)
      + ". What kind of property? Apartment, House, Villa, PG, Plot, Commercial, or Other.";
  }

  private static String locationPrompt() {
    return "Which city and locality? Reply like: BLR, Whitefield.";
  }

  private static String bedroomsPrompt() {
    return "How many bedrooms? Reply with a number 0-20.";
  }

  private static String pricePrompt() {
    return "What's the expected price in rupees? Reply with a number (e.g. 25000).";
  }

  private static String titlePrompt() {
    return "Give your listing a short title.";
  }

  private static String descriptionPrompt() {
    return "Add a short description for buyers or tenants.";
  }

  private String promptFor(String state, Map<String, String> draft) {
    return switch (state) {
      case STATE_PROPERTY_TYPE -> propertyTypePrompt(draft.get("listingType"));
      case STATE_LOCATION -> draft.containsKey("cityCode")
        ? "Which locality? Just reply with the locality name." : locationPrompt();
      case STATE_BEDROOMS -> bedroomsPrompt();
      case STATE_PRICE -> pricePrompt();
      case STATE_TITLE -> titlePrompt();
      case STATE_DESCRIPTION -> descriptionPrompt();
      default -> listingTypePrompt();
    };
  }

}
