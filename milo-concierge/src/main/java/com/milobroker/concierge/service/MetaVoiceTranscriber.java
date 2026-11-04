package com.milobroker.concierge.service;

import com.milobroker.concierge.json.Jsons;
import jakarta.json.Json;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Transcribes voice-note audio with Meta's Muse Voice Transcribe model via
 * {@code POST /v1/asr/transcribe}. Sends the doc-shaped multipart body (a
 * {@code request} JSON part with mode, model, WAV encoding, language and
 * keyword biasing, plus the {@code audio} WAV part) and returns the plain
 * {@code transcript}. PUSH_TO_TALK keeps it to a single turn per voice note,
 * so the caller feeds the transcript straight into the conversation router
 * exactly like a typed message.
 */
@Service
public class MetaVoiceTranscriber {
  private static final String TRANSCRIBE_PATH = "/v1/asr/transcribe";
  private final RestClient rest;
  private final String transcribeUrl;
  private final String model;
  private final String apiKey;
  private final boolean enabled;
  private final String languageBiasCsv;
  private final String keywordsCsv;

  public MetaVoiceTranscriber(RestClient.Builder builder,
      @Value("${milobroker.voice.transcribe-url:https://api.meta.ai/v1/asr/transcribe}")
      String transcribeUrl,
      @Value("${milobroker.voice.model:muse-voice-transcribe-1.0}") String model,
      @Value("${milobroker.meta.api-key:}") String apiKey,
      @Value("${milobroker.voice.enabled:true}") boolean enabled,
      @Value("${milobroker.voice.language-bias:Kannada,Hindi,English}") String languageBiasCsv,
      @Value("${milobroker.voice.keywords:BHK,Bengaluru,Whitefield,Indiranagar,rent}")
      String keywordsCsv) {
    this.rest = builder.build();
    this.transcribeUrl = transcribeUrl == null || transcribeUrl.isBlank()
      ? "https://api.meta.ai" + TRANSCRIBE_PATH : transcribeUrl;
    this.model = model == null ? "" : model;
    this.apiKey = apiKey == null ? "" : apiKey;
    this.enabled = enabled;
    this.languageBiasCsv = languageBiasCsv == null ? "" : languageBiasCsv;
    this.keywordsCsv = keywordsCsv == null ? "" : keywordsCsv;
  }

  public boolean isConfigured() {
    return enabled && !model.isBlank() && !apiKey.isBlank();
  }

  public String transcribe(byte[] wavAudio, String sessionId) {
    if (!isConfigured()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Voice transcription is not configured");
    }
    if (wavAudio == null || wavAudio.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Voice audio was empty");
    }
    String safeSession = sessionId == null ? "voice-note"
      : sessionId.replaceAll("[^A-Za-z0-9_-]", "-");
    MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
    HttpHeaders requestHeaders = new HttpHeaders();
    requestHeaders.setContentType(MediaType.APPLICATION_JSON);
    body.add("request", new HttpEntity<>(buildRequestJson(), requestHeaders));
    ByteArrayResource audioResource = new ByteArrayResource(wavAudio) {
      @Override
      public String getFilename() {
        return "voice-note.wav";
      }
    };
    HttpHeaders audioHeaders = new HttpHeaders();
    audioHeaders.setContentDispositionFormData("audio", "voice-note.wav");
    audioHeaders.setContentType(MediaType.parseMediaType("audio/wav"));
    body.add("audio", new HttpEntity<>(audioResource, audioHeaders));
    String json;
    try {
      json = rest.post().uri(transcribeUrl + "?sessionId={sid}", safeSession)
          .headers(h -> h.setBearerAuth(apiKey))
          .contentType(MediaType.MULTIPART_FORM_DATA)
          .body(body)
          .retrieve().body(String.class);
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "Voice transcription failed", ex);
    }
    String text = Jsons.string(Jsons.parseObject(json == null ? "{}" : json, "Transcript"),
      "transcript");
    if (text == null || text.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "Voice transcription was empty");
    }
    return text.trim();
  }

  String buildRequestJson() {
    var languages = Json.createArrayBuilder();
    Arrays.stream(languageBiasCsv.split(","))
      .map(String::trim).filter(s -> !s.isEmpty()).forEach(languages::add);
    var keywords = Json.createArrayBuilder();
    Arrays.stream(keywordsCsv.split(","))
      .map(String::trim).filter(s -> !s.isEmpty()).forEach(keywords::add);
    return Json.createObjectBuilder()
      .add("mode", "PUSH_TO_TALK")
      .add("model", model)
      .add("audioEncoding", "WAV")
      .add("languageBias", languages)
      .add("keywords", keywords)
      .build().toString();
  }
}
