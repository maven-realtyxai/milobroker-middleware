package com.milobroker.concierge.repository;
import com.milobroker.concierge.domain.InboundEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
/** Inbound envelopes, idempotent on the channel message id. */
public interface InboundEventRepository extends JpaRepository<InboundEvent, UUID> {
  Optional<InboundEvent> findByChannelMessageId(String channelMessageId);
}
