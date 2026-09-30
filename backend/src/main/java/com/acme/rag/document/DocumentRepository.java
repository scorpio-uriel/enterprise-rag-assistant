package com.acme.rag.document;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

  boolean existsBySha256(String sha256);

  List<Document> findAllByOrderByCreatedAtDesc();
}
