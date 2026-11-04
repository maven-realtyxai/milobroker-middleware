package com.milobroker.concierge.repository;
import com.milobroker.concierge.domain.ConversationSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/** Conversation threads by id. */
public interface ConversationSessionRepository extends JpaRepository<ConversationSession, UUID> {
  Optional<ConversationSession> findFirstByChannelAndChannelUserIdOrderByUpdatedAtDesc(
    String channel, String channelUserId);
}
