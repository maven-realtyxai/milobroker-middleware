package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** WAV-header contract and failure mapping. Real ffmpeg runs on EC2, not here. */
class FfmpegAudioConverterTest {

  private static byte[] wav(int channels, int sampleRate, int bits) {
    ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put(new byte[] {'R', 'I', 'F', 'F'});
    header.putInt(36);
    header.put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
    header.putInt(16);
    header.putShort((short) 1);
    header.putShort((short) channels);
    header.putInt(sampleRate);
    header.putInt(sampleRate * channels * bits / 8);
    header.putShort((short) (channels * bits / 8));
    header.putShort((short) bits);
    header.put(new byte[] {'d', 'a', 't', 'a'});
    header.putInt(0);
    return header.array();
  }

  @Test
  void acceptsNativeRateMonoPcm() {
    assertDoesNotThrow(() -> FfmpegAudioConverter.validateWav(wav(1, 24000, 16)));
    assertDoesNotThrow(() -> FfmpegAudioConverter.validateWav(wav(1, 16000, 16)));
  }

  @Test
  void rejectsStereoEightBitAndJunk() {
    assertBad(wav(2, 24000, 16));
    assertBad(wav(1, 24000, 8));
    assertBad(wav(1, 44100, 16));
    assertBad(new byte[] {'O', 'g', 'g', 'S', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
  }

  private static void assertBad(byte[] wav) {
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> FfmpegAudioConverter.validateWav(wav));
    assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
  }

  @Test
  void missingBinaryIsUnavailable() {
    FfmpegAudioConverter converter = new FfmpegAudioConverter(
      "definitely-not-a-real-binary-xyz", 5);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> converter.toWav(new byte[] {1, 2, 3}));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
  }

  @Test
  void emptyInputIsRejected() {
    FfmpegAudioConverter converter = new FfmpegAudioConverter("ffmpeg", 5);
    ResponseStatusException ex = assertThrows(ResponseStatusException.class,
      () -> converter.toWav(new byte[0]));
    assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }
}
