package com.enterprise.securechat.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId ORDER BY m.createdAt DESC")
    List<Message> findLatestByConversationId(
            @Param("conversationId") UUID conversationId,
            Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.conversationId = :conversationId ORDER BY m.createdAt ASC")
    List<Message> findAllByConversationIdOrderByCreatedAtAsc(@Param("conversationId") UUID conversationId);

    @Query(value = """
            SELECT TO_CHAR(DATE_TRUNC('day', created_at), 'YYYY-MM-DD') AS day,
                   SUM(dlp_redacted) AS total_redacted
            FROM messages
            WHERE role = 'assistant' AND dlp_redacted > 0
            GROUP BY DATE_TRUNC('day', created_at)
            ORDER BY DATE_TRUNC('day', created_at) DESC
            LIMIT 30
            """, nativeQuery = true)
    List<Object[]> findDlpDensityByDay();
}
