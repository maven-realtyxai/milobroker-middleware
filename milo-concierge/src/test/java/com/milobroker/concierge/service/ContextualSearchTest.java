package com.milobroker.concierge.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.milobroker.concierge.domain.ConversationSession;
import com.milobroker.concierge.dto.OnboardingDtos.ProfileDto;
import com.milobroker.concierge.repository.ConversationSessionRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ContextualSearchTest {
  @Test void bareLocalityRetainsFiltersAndHistoryAcrossRouterInstances() {
    var builder = RestClient.builder();
    var server = MockRestServiceServer.bindTo(builder).build();
    for (String locality : new String[]{"KORAMANGALA", "DEVANAHALLI", "WHITEFIELD"}) {
      server.expect(requestTo("http://marketplace/api/v1/search"))
        .andExpect(content().json("{\"cityCode\":\"BLR\",\"localityCode\":\"" + locality
          + "\",\"listingType\":\"RENT\",\"bedrooms\":2,\"maximumBudgetInr\":30000}"))
        .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));
    }
    var repository = mock(ConversationSessionRepository.class);
    AtomicReference<ConversationSession> saved = new AtomicReference<>();
    when(repository.findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc("whatsapp", "u1"))
      .thenAnswer(i -> Optional.ofNullable(saved.get()));
    when(repository.save(any())).thenAnswer(i -> { saved.set(i.getArgument(0)); return saved.get(); });
    var onboarding = mock(OnboardingService.class);
    when(onboarding.profile("u1")).thenReturn(new ProfileDto("u1", "Ravi", "RENTAL", "KA", "BLR", true));
    var whatsapp = mock(WhatsAppClient.class);
    var model = mock(ModelClient.class);
    var post = mock(PostPropertyFlowService.class);
    var router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model,
      null, post, null, builder, "http://marketplace");
    router.setConversationMemory(new ConversationMemory(repository));
    router.reply("u1", "Ravi", "Search 2 bhk rent in Koramangala under 30000", "text");
    // A restart must not lose the previous search request or assistant response.
    router = new ConversationRouter(onboarding, whatsapp, new InputGuardrailService(), model,
      null, post, null, builder, "http://marketplace");
    var memory = new ConversationMemory(repository);
    router.setConversationMemory(memory);
    router.reply("u1", "Ravi", "Devanahalli", "text");
    router.reply("u1", "Ravi", "Whitefield", "text");
    assertTrue(memory.history("u1").contains("User: Devanahalli"));
    assertTrue(memory.history("u1").contains("Milo: Hi Ravi! I couldn't find"));
    verify(model, never()).chat(any());
    verify(model, never()).classifyProperty(any(), any());
    server.verify();
    // A new post switches the task instead of reusing stale search state.
    router.reply("u1", "Ravi", "post a property", "text");
    assertNull(memory.searchFilters("u1"));
    verify(post).handle("u1", "Ravi", "post a property", "text");
  }
}
