package com.acme.rag.document;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contrôleur provisoire (J2) pour tester la règle ADMIN ; remplacé au jalon J3. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

  @GetMapping
  public List<Object> list() {
    return List.of();
  }
}
