package com.acme.rag.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

  boolean existsBySha256(String sha256);

  List<Document> findAllByOrderByCreatedAtDesc();

  List<Document> findAllByStatus(DocumentStatus status);

  /**
   * {@code PENDING → INDEXING} en une seule requête : si une suppression est passée entre-temps, 0
   * ligne est modifiée et l'indexation n'a pas lieu. Lire puis sauvegarder l'entité ne serait pas
   * sûr : {@code save} ré-insérerait une ligne tout juste supprimée.
   *
   * @return 1 si l'indexation peut commencer, 0 sinon
   */
  @Transactional
  @Modifying
  @Query(
      "update Document d set d.status = com.acme.rag.document.DocumentStatus.INDEXING,"
          + " d.updatedAt = :now"
          + " where d.id = :id and d.status = com.acme.rag.document.DocumentStatus.PENDING")
  int startIndexing(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Supprime la ligne sauf si l'indexation a démarré entre la lecture et la suppression.
   *
   * @return 1 si supprimée, 0 si le document est (devenu) {@code INDEXING}
   */
  @Transactional
  @Modifying
  @Query(
      "delete from Document d"
          + " where d.id = :id and d.status <> com.acme.rag.document.DocumentStatus.INDEXING")
  int deleteUnlessIndexing(@Param("id") UUID id);
}
