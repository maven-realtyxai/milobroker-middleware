package com.milobroker.concierge.service;

import com.milobroker.concierge.json.Jsons;
import jakarta.json.JsonObject;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns an incoming WhatsApp voice note into chat text: download the Opus
 * bytes, convert to WAV, transcribe, and hand the transcript back. The
 * webhook calls here before the guardrail/router pipeline; a present
 * transcript is fed back as the message body so voice input flows through
 * onboarding, post, and search exactly like typed text. Any failure
 * (unconfigured, download error, conversion error, transcription error)
 * returns empty so the webhook can fall back to the static "could not
 * listen" reply instead of breaking the session.
 */
@Service
public class VoiceNoteService {
  private static final Logger log = LoggerFactory.getLogger(VoiceNoteService.class);
  private final WhatsAppMediaClient media;
  private final AudioConverter converter;
  private final MetaVoiceTranscriber transcriber;

  public VoiceNoteService(WhatsAppMediaClient media, AudioConverter converter,
      MetaVoiceTranscriber transcriber) {
    this.media = media;
    this.converter = converter;
    this.transcriber = transcriber;
  }

  public Optional<String> transcribeMessage(JsonObject message) {
    if (message == null
        || !InputGuardrailService.isVoiceType(Jsons.string(message, "type"))) {
      return Optional.empty();
    }
    if (media == null || converter == null || transcriber == null
        || !media.isConfigured() || !transcriber.isConfigured()) {
      return Optional.empty();
    }
    String mediaId = null;
    for (String key : new String[] {"audio", "voice"}) {
      String id = Jsons.string(Jsons.object(message, key), "id");
      if (id != null && !id.isBlank()) {
        mediaId = id;
        break;
      }
    }
    if (mediaId == null) {
      return Optional.empty();
    }
    try {
      WhatsAppMediaClient.AudioBytes audio = media.downloadAudio(mediaId);
      byte[] wav = converter.toWav(audio.data());
      String from = Jsons.string(message, "from");
      return Optional.of(transcriber.transcribe(wav,
        "wa-" + (from == null ? "unknown" : from) + "-" + Instant.now().toEpochMilli()));
    } catch (Exception ex) {
      log.warn("Voice transcription failed, falling back to text reply: {}", ex.toString());
      return Optional.empty();
    }
  }
}
