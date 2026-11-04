package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ConversationMemoryTest {
  @Test void keepsRecentHistoryAndOtherSessionDataAcrossInstances() {
    var repository = mock(ConversationSessionRepository.class);
    var session = new ConversationSession();
    session.setContext("{\"postDraft\":{\"title\":\"Home\"}}");
    AtomicReference<ConversationSession> stored = new AtomicReference<>(session);
    when(repository.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc(anyString(), anyString()))
      .thenAnswer(i -> Optional.ofNullable(stored.get()));
    when(repository.save(any(ConversationSession.class))).thenAnswer(i -> {
      stored.set(i.getArgument(0)); return stored.get();
    });
    var memory = new ConversationMemory(repository);
    memory.rememberLocality("123", "DEVANAHALLI");
    for (int i = 0; i < 5; i++) memory.record("123", "request " + i, "reply " + i);
    var restored = new ConversationMemory(repository);
    assertEquals("DEVANAHALLI", restored.locality("123"));
    assertTrue(restored.history("123").contains("request 4"));
    assertFalse(restored.history("123").contains("request 0"));
    assertTrue(session.getContext().contains("postDraft"));
  }
}
