package com.milobroker.concierge.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link AudioConverter} shelling out to ffmpeg with the exact recipe from
 * the transcription docs: mono, 16-bit PCM, 24 kHz native rate, metadata
 * stripped. Temp files are always cleaned up; every failure surfaces as an
 * HTTP-mapped exception the caller turns into the static voice fallback.
 * The output WAV header is validated before it leaves this class so a
 * corrupt transcode never reaches the transcription endpoint.
 */
@Service
public class FfmpegAudioConverter implements AudioConverter {
  private static final Logger log = LoggerFactory.getLogger(FfmpegAudioConverter.class);
  private final String ffmpegPath;
  private final long timeoutSeconds;

  public FfmpegAudioConverter(
      @Value("${milobroker.voice.ffmpeg-path:ffmpeg}") String ffmpegPath,
      @Value("${milobroker.voice.convert-timeout-seconds:30}") long timeoutSeconds) {
    this.ffmpegPath = ffmpegPath == null || ffmpegPath.isBlank() ? "ffmpeg" : ffmpegPath;
    this.timeoutSeconds = Math.max(timeoutSeconds, 1);
  }

  @Override
  public byte[] toWav(byte[] source) {
    if (source == null || source.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Voice note was empty");
    }
    Path dir = null;
    try {
      dir = Files.createTempDirectory("voice-convert-");
      Path in = dir.resolve("in.ogg");
      Path out = dir.resolve("out.wav");
      Files.write(in, source);
      Process process = new ProcessBuilder(ffmpegPath,
          "-hide_banner", "-loglevel", "error",
          "-y", "-i", in.toString(),
          "-ac", "1", "-ar", "24000", "-c:a", "pcm_s16le",
          "-map_metadata", "-1", out.toString())
        .redirectErrorStream(true)
        .start();
      boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Voice conversion timed out");
      }
      if (process.exitValue() != 0 || !Files.exists(out)) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
            "Voice conversion failed");
      }
      byte[] wav = Files.readAllBytes(out);
      validateWav(wav);
      return wav;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Voice conversion was interrupted", ex);
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Voice conversion is not available", ex);
    } finally {
      if (dir != null) {
        deleteQuietly(dir.resolve("in.ogg"));
        deleteQuietly(dir.resolve("out.wav"));
        deleteQuietly(dir);
      }
    }
  }

  /** Package-visible for tests: checks the RIFF/WAVE PCM mono 16-bit contract. */
  static void validateWav(byte[] wav) {
    if (wav == null || wav.length < 44
        || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F'
        || wav[8] != 'W' || wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "Voice conversion produced an invalid WAV file");
    }
    ByteBuffer header = ByteBuffer.wrap(wav, 0, 44).order(ByteOrder.LITTLE_ENDIAN);
    int audioFormat = Short.toUnsignedInt(header.getShort(20));
    int channels = Short.toUnsignedInt(header.getShort(22));
    int sampleRate = header.getInt(24);
    int bitsPerSample = Short.toUnsignedInt(header.getShort(34));
    if (audioFormat != 1 || channels != 1 || bitsPerSample != 16
        || (sampleRate != 16000 && sampleRate != 24000)) {
      log.warn("Unexpected WAV header: format={} channels={} rate={} bits={}",
        audioFormat, channels, sampleRate, bitsPerSample);
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
          "Voice conversion produced an unsupported WAV file");
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }
}
