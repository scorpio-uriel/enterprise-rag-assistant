package com.acme.rag.document;

import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Accès réservé au rôle ADMIN (voir {@code SecurityConfig}). Aucune logique métier ici. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

  private final DocumentService documentService;

  public DocumentController(DocumentService documentService) {
    this.documentService = documentService;
  }

  /** {@code 202 Accepted} : reçu, l'indexation se fera plus tard (le client suit le statut). */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<UploadResponse> upload(
      @RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.accepted().body(documentService.upload(file, jwt.getSubject()));
  }

  @GetMapping
  public List<DocumentDto> list() {
    return documentService.list();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    documentService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
