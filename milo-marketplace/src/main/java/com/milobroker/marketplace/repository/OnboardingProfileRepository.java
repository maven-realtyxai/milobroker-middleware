package com.milobroker.marketplace.repository;
import com.milobroker.marketplace.domain.OnboardingProfile;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
/** Read/write access to user records keyed by WhatsApp user id. */
public interface OnboardingProfileRepository extends JpaRepository<OnboardingProfile, String> {
  /** Atomically creates or claims the one-time welcome for an incomplete user. */
  @Modifying
  @Query(value = """
      INSERT INTO users
        (channel_user_id, display_name, completed, welcome_sent, created_at, updated_at)
      VALUES (:channelUserId, :displayName, false, true, now(), now())
      ON CONFLICT (channel_user_id) DO UPDATE
        SET display_name = COALESCE(NULLIF(users.display_name, ''), EXCLUDED.display_name),
            welcome_sent = true,
            updated_at = now()
      WHERE users.completed = false
        AND users.welcome_sent = false
      """, nativeQuery = true)
  int claimWelcome(@Param("channelUserId") String channelUserId,
      @Param("displayName") String displayName);

  @Modifying
  @Query(value = "UPDATE users SET welcome_sent = false, updated_at = now() "
      + "WHERE channel_user_id = :channelUserId AND completed = false", nativeQuery = true)
  int releaseWelcome(@Param("channelUserId") String channelUserId);
}
