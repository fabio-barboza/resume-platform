package dev.resumeplatform.resumeai.infra.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import dev.resumeplatform.resumeai.infra.entity.ChatMessageEntity;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findBySessionIdOrderByIdAsc(String sessionId);
}
