package com.milobroker.concierge.service;

/**
 * Converts an incoming voice-note container (WhatsApp sends Opus in OGG)
 * into the mono 16-bit PCM WAV the transcription endpoint accepts.
 */
public interface AudioConverter {
  /** Returns WAV bytes, or throws when conversion is unavailable or fails. */
  byte[] toWav(byte[] source);
}
