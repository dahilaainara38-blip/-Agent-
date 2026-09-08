package com.example.demo.chat.repository.sqlite;

import com.example.demo.chat.entity.sqlite.VectorStore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface VectorStoreRepository extends JpaRepository<VectorStore, Long> {
    Optional<VectorStore> findByDocumentId(String documentId);
    List<VectorStore> findBySourceId(String sourceId);
    List<VectorStore> findByConversationId(String conversationId);
    void deleteByDocumentId(String documentId);
    void deleteBySourceId(String sourceId);
    void deleteByConversationId(String conversationId);
    long countBySourceId(String sourceId);

    /** 清理无主向量（owner/source/conversation 均空）：runtime 检索规则下永不可见的历史死数据。 */
    @Transactional
    @Modifying
    @Query("delete from VectorStore v where v.ownerId is null and v.sourceId is null and v.conversationId is null")
    int deleteOrphanedVectors();
}