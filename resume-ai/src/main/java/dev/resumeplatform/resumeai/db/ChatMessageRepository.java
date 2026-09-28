package dev.resumeplatform.resumeai.db;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySessionIdOrderByIdAsc(String sessionId);
}
