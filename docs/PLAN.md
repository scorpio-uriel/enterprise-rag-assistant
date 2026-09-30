# Enterprise RAG Assistant — Plan d'architecture et de réalisation

> Compagnon de [`SPEC.md`](./SPEC.md). La SPEC dit **quoi** construire ; ce document dit **comment**, et **pourquoi** ainsi.
> Date : 2026-09-30 · Cible : Java 21 · Spring Boot 4.1 · Spring AI 2.0 · PostgreSQL 16 + pgvector · React + TypeScript

## Comment lire ce plan

Chaque choix technique est présenté en trois temps :

> **Quoi** : la décision.
> **Pourquoi** : le problème qu'elle résout, expliqué pour quelqu'un qui découvre Spring Boot.
> **Écarté** : l'alternative raisonnable et la raison du refus.

Les jalons (§ 8) sont conçus pour être **livrables en une à deux journées** : à la fin de chacun, le projet compile, les tests passent et une démonstration est possible.

> ⚠️ **Spring AI 2.0 est récent (GA en juin 2026).** La plupart des tutoriels en ligne ciblent Spring AI 1.x. Les noms de classes cités ici (`VectorStore`, `SearchRequest`, `TokenTextSplitter`, `PagePdfDocumentReader`, `ChatClient`…) sont stables dans leurs principes, mais **vérifiez toujours la signature exacte dans la [documentation de référence](https://docs.spring.io/spring-ai/reference/)** avant de copier un exemple trouvé ailleurs.

---

## 1. Vue d'ensemble

### 1.1 Organisation du dépôt (monorepo)

```
enterprise-rag-assistant/
├── backend/                 # Spring Boot (Maven)
├── frontend/                # React + TypeScript (Vite)
├── demo-data/               # corpus fictif Acme (7 documents)
├── eval/                    # questions.yaml + rapports d'évaluation
├── docs/                    # SPEC.md, PLAN.md, captures, GIF
├── docker-compose.yml       # stack complète
├── docker-compose.dev.yml   # uniquement postgres + ollama (dev local)
├── .env.example
└── .github/workflows/ci.yml
```

**Quoi :** un seul dépôt pour le back, le front et l'infrastructure.
**Pourquoi :** Un nouveau développeur rejoignant l'équipe peut cloner un seul dépôt et lancer toute la stack en une seule commande. De plus, une fonctionnalité qui touche le back et le front tient dans une seule Pull Request (PR), ce qui rend l'historique beaucoup plus lisible.
**Écarté :** deux dépôts séparés. La synchronisation des versions et la CI se compliquent sans rien apporter à cette échelle.

### 1.2 Conteneurs à l'exécution

```
 Navigateur
     │  HTTP :8080
     ▼
┌──────────────┐  /api/*   ┌────────────────┐   JDBC   ┌──────────────────────┐
│  frontend    │ ────────▶ │    backend     │ ───────▶ │ postgres + pgvector  │
│  (nginx)     │           │ (Spring Boot)  │          │ tables + vector_store│
└──────────────┘           └───────┬────────┘          └──────────────────────┘
                                   │ HTTP :11434
                                   ▼
                           ┌────────────────┐
                           │     ollama     │  llama3.1:8b + nomic-embed-text
                           └────────────────┘
```

nginx sert les fichiers statiques du front **et** relaie `/api/*` vers le backend. Le navigateur ne parle donc qu'à une seule origine : **aucune configuration CORS n'est nécessaire**.

---

## 2. Backend

### 2.1 Un monolithe modulaire, organisé par fonctionnalité

```
backend/src/main/java/com/acme/rag/
├── RagApplication.java
├── common/
│   ├── RagProperties.java            # configuration typée (topK, seuil, chunks…)
│   ├── AsyncConfig.java              # pool de threads pour l'indexation
│   └── GlobalExceptionHandler.java   # erreurs → ProblemDetail JSON
├── auth/
│   ├── User.java, Role.java, UserRepository.java
│   ├── AuthController.java           # POST /api/auth/login, GET /api/me
│   ├── TokenService.java             # fabrique les JWT
│   ├── SecurityConfig.java           # règles d'accès, décodage JWT
│   └── DataSeeder.java               # crée admin/user au démarrage
├── document/
│   ├── Document.java, DocumentStatus.java, DocumentRepository.java
│   ├── DocumentController.java       # /api/documents
│   ├── DocumentService.java
│   ├── FileValidator.java            # taille, extension, type MIME réel
│   ├── FileStorage.java              # écrit/lit/supprime sur le volume
│   └── DocumentUploadedEvent.java
├── ingestion/
│   ├── IngestionListener.java        # écoute DocumentUploadedEvent
│   ├── IngestionService.java         # lecture → découpage → embeddings
│   ├── DocumentReaderFactory.java    # choisit le lecteur selon le type
│   └── StuckIndexingRecovery.java    # au démarrage : INDEXING → FAILED
├── chat/
│   ├── ChatController.java           # POST /api/chat (SSE)
│   ├── ChatService.java              # orchestre retrieval → LLM → persistance
│   ├── RetrievalService.java         # recherche vectorielle + seuil
│   ├── RagPromptFactory.java         # construit le prompt système + contexte
│   └── dto/ (ChatRequest, SourceDto, ChatEvent…)
└── conversation/
    ├── Conversation.java, Message.java, MessageRole.java
    ├── ConversationRepository.java, MessageRepository.java
    ├── ConversationController.java   # /api/conversations
    └── ConversationService.java
```

**Quoi :** un seul déployable (un « monolithe »), mais découpé **par fonctionnalité** (`auth`, `document`, `chat`…) plutôt que **par couche** (`controllers/`, `services/`, `repositories/`).
**Pourquoi :** quand vous travaillez sur l'import de documents, tout ce qui le concerne est dans `document/`. Dans un découpage par couche, il faudrait ouvrir trois dossiers pour une seule fonctionnalité. Les dépendances entre modules deviennent aussi visibles : `chat` dépend de `conversation`, mais `document` ne dépend jamais de `chat`.
**Écarté :** les microservices. Ils ajoutent du réseau, des déploiements multiples et de la cohérence distribuée, sans aucun bénéfice pour environ 100 documents et quelques utilisateurs.

> 💡 **Rappel Spring :** une classe annotée `@Service`, `@RestController`, `@Repository` ou `@Component` devient un **bean**. Spring la crée une seule fois et l'**injecte** là où elle est demandée dans un constructeur. Vous n'écrivez jamais `new DocumentService(...)` : c'est l'**injection de dépendances**. Préférez toujours l'injection **par constructeur**, sans `@Autowired` sur les champs. Les dépendances sont alors explicites, `final`, et faciles à remplacer par des mocks dans les tests.

### 2.2 Dépendances Maven principales

| Dépendance | Rôle |
|---|---|
| `spring-boot-starter-webmvc` | API REST (Tomcat + Spring MVC) |
| `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` | Authentification, validation des JWT |
| `spring-boot-starter-data-jpa` | Entités, repositories |
| `spring-boot-starter-validation` | `@Valid`, `@NotBlank`… |
| `spring-boot-starter-flyway` + `flyway-database-postgresql` | Migrations SQL versionnées |
| `spring-boot-starter-actuator` | `/actuator/health` |
| `spring-ai-starter-model-ollama` | `ChatModel` et `EmbeddingModel` Ollama |
| `spring-ai-starter-vector-store-pgvector` | `VectorStore` sur pgvector |
| `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader` | Lecture PDF / DOCX |
| Tests : `spring-boot-starter-test`, `spring-security-test`, `spring-boot-testcontainers`, Testcontainers PostgreSQL, `awaitility` | |

**Quoi :** des **starters**.
**Pourquoi :** un starter est un « paquet » de dépendances cohérentes, accompagné d'une **auto-configuration**. Si Spring voit `spring-ai-starter-model-ollama` sur le classpath et `spring.ai.ollama.base-url` dans la configuration, il crée tout seul un bean `ChatModel` prêt à l'emploi. C'est la « magie » de Spring Boot, et ce n'est que du code conditionnel (`@ConditionalOnClass`, `@ConditionalOnProperty`) que vous pouvez lire.

> ⚠️ **Spring Boot 4** a découpé certains starters, par exemple `spring-boot-starter-web` → `spring-boot-starter-webmvc`, et Flyway demande son propre starter. Générez le projet sur [start.spring.io](https://start.spring.io) plutôt que de recopier un `pom.xml` d'un tutoriel Boot 3. Utilisez aussi le **BOM** `spring-ai-bom` pour aligner les versions de Spring AI.

**Maven plutôt que Gradle :** le plus répandu dans les offres d'emploi Java en entreprise, déclaratif, et avec moins de pièges pour débuter. Le wrapper `./mvnw` est commité, donc aucune installation n'est requise.

### 2.3 Configuration : `application.yml` + `@ConfigurationProperties`

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/rag}
    username: ${DB_USER:rag}
    password: ${DB_PASSWORD:rag}
  jpa.hibernate.ddl-auto: validate
  servlet.multipart: { max-file-size: 20MB, max-request-size: 21MB }
  ai:
    ollama:
      base-url: ${OLLAMA_URL:http://localhost:11434}
      chat.options: { model: llama3.1:8b, temperature: 0.1 }
      embedding.options.model: nomic-embed-text
    vectorstore.pgvector:
      initialize-schema: false        # c'est Flyway qui crée la table
      dimensions: 768
      distance-type: COSINE_DISTANCE
      index-type: HNSW
rag:
  top-k: 4
  similarity-threshold: 0.55          # à calibrer au jalon J11
  chunk-size: 800
  chunk-overlap: 100
  history-window: 6                   # nombre de messages repris dans le prompt
  storage-dir: ${STORAGE_DIR:./data/uploads}
security:
  jwt:
    secret: ${JWT_SECRET}             # ≥ 32 octets, jamais commité
    ttl: 1h
```

```java
@Validated
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
    @Min(1) @Max(20) int topK,
    @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
    @Min(100) int chunkSize,
    @Min(0) int chunkOverlap,
    @Min(0) int historyWindow,
    @NotBlank String storageDir) {}
```

**Quoi :** regrouper les réglages métier dans un `record` typé et validé.
**Pourquoi :** avec `@Value("${rag.top-k}")` éparpillé dans dix classes, une faute de frappe ne se voit qu'à l'exécution. Avec `@ConfigurationProperties`, l'application **refuse de démarrer** si `similarity-threshold: 1.5`, et votre IDE propose l'autocomplétion. La syntaxe `${VAR:défaut}` lit une variable d'environnement, avec une valeur par défaut pour le développement. C'est ainsi que les secrets restent hors du dépôt (NF3).

### 2.4 Sécurité : JWT émis par l'application

**Quoi :** utiliser le module **OAuth2 Resource Server** de Spring Security pour **valider** les JWT, et un `JwtEncoder` (Nimbus, algorithme HS256 avec secret partagé) pour **les émettre** au login.
**Pourquoi :** c'est bien un « JWT maison » (pas de Keycloak), mais **vous n'écrivez pas de code cryptographique**. Le filtre qui lit l'en-tête `Authorization: Bearer …`, vérifie la signature et l'expiration puis renvoie `401` est fourni et éprouvé. Vous écrivez seulement :
1. `TokenService` : `claims = { sub: email, roles: ["ADMIN"], exp: now+1h }` → `jwtEncoder.encode(...)`.
2. Un `JwtAuthenticationConverter` qui transforme le claim `roles` en autorités `ROLE_ADMIN`.
3. Les règles d'accès :

```java
http
  .csrf(csrf -> csrf.disable())                  // API stateless, pas de cookie de session
  .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
  .authorizeHttpRequests(auth -> auth
      .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll() // voir le piège SSE
      .requestMatchers(POST, "/api/auth/login").permitAll()
      .requestMatchers("/actuator/health").permitAll()
      .requestMatchers("/api/documents/**").hasRole("ADMIN")
      .anyRequest().authenticated())
  .oauth2ResourceServer(o -> o.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));
```

**Login :** `AuthController` délègue à un `AuthenticationManager` configuré avec un `DaoAuthenticationProvider`, qui charge l'utilisateur via un `UserDetailsService` branché sur `UserRepository` et compare le hash **BCrypt**. En cas d'échec, il renvoie `401` avec un message générique (AC1.2 : ne pas révéler si l'email existe).

**Pourquoi CSRF désactivé ?** L'attaque CSRF exploite le fait que le navigateur envoie **automatiquement** les cookies. Notre token est envoyé **manuellement** dans un en-tête, donc un site tiers ne peut pas le joindre à une requête.

**Écarté :** un filtre `OncePerRequestFilter` avec la bibliothèque jjwt. C'est le tutoriel classique, mais c'est plus de code à maintenir et à tester, pour réinventer ce que Spring fournit déjà.

**Comptes de démo :** `DataSeeder implements ApplicationRunner` s'exécute une fois au démarrage et crée `admin@acme.local` / `user@acme.local` **s'ils n'existent pas**, avec les mots de passe `ADMIN_PASSWORD` / `USER_PASSWORD` issus de l'environnement.

### 2.5 Import et indexation asynchrone

```
POST /api/documents (multipart)
  │
  ├─ FileValidator : taille ≤ 20 Mo, extension ∈ {pdf, md, txt, docx},
  │                  type MIME réel détecté par Tika (lit les premiers octets)
  ├─ SHA-256 du contenu → déjà en base ? → 409
  ├─ FileStorage : copie sur le volume  /data/uploads/{uuid}.{ext}
  ├─ INSERT document (status = PENDING)
  ├─ publishEvent(DocumentUploadedEvent)
  └─ 202 { id, status: PENDING }
                  ┆ après le COMMIT de la transaction
                  ▼
IngestionListener (@TransactionalEventListener(AFTER_COMMIT) + @Async)
  ├─ status = INDEXING
  ├─ DocumentReaderFactory → List<org.springframework.ai.document.Document>
  │     PDF  → PagePdfDocumentReader  (un Document par page, metadata.page)
  │     DOCX → TikaDocumentReader
  │     MD/TXT → TextReader
  ├─ TokenTextSplitter(chunkSize, overlap)  → chunks
  ├─ chaque chunk reçoit metadata { documentId, fileName, page }
  ├─ vectorStore.add(chunks)   ← calcule les embeddings via Ollama puis INSERT
  ├─ status = INDEXED, chunk_count = n
  └─ en cas d'exception : vectorStore.delete("documentId == '…'"), status = FAILED, error_message
```

Chaque étape expliquée :

- **Validation du type MIME réel.** L'extension se falsifie en un renommage (`virus.exe` → `virus.pdf`, AC2.4). Tika lit les « nombres magiques » en début de fichier (`%PDF-`, `PK` pour un docx…) pour identifier le vrai format.
- **SHA-256 pour les doublons.** Deux fichiers au contenu identique ont la même empreinte, même avec des noms différents. Une contrainte `UNIQUE` en base garantit la règle même si deux imports arrivent en même temps.
- **Stocker le fichier sur disque avant de répondre.** Le `MultipartFile` fourni par Spring est **supprimé à la fin de la requête HTTP**. Or l'indexation se déroule *après* la réponse `202`. Sans copie sur un volume, le thread d'indexation lirait un fichier disparu.
- **Pourquoi `202 Accepted` et pas `201 Created` ?** `202` signifie « reçu, traitement en cours ». Le client sait qu'il doit interroger le statut (polling).
- **`@TransactionalEventListener(AFTER_COMMIT)`, le piège classique.** Si l'indexation démarrait directement dans `DocumentService.upload()`, elle pourrait s'exécuter dans un autre thread **avant** que la transaction qui insère le document soit validée. Le thread d'indexation ne trouverait alors pas le document en base. Publier un événement traité *après le commit* supprime cette condition de course.
- **`@Async` avec un pool borné.** `@Async` exécute la méthode dans un autre thread : la requête HTTP rend la main immédiatement. `AsyncConfig` déclare un `ThreadPoolTaskExecutor` (2 threads, file de 50), ce qui évite que 30 imports simultanés saturent Ollama et la mémoire. N'oubliez pas `@EnableAsync`.
- **Nettoyage en cas d'échec** (AC3.2). Si l'erreur survient à mi-parcours, certains chunks sont peut-être déjà insérés. Supprimer par `documentId` garantit qu'un document `FAILED` ne laisse aucun vecteur « fantôme » qui polluerait les réponses.
- **Récupération au démarrage.** Si l'application s'arrête pendant une indexation, le document reste bloqué en `INDEXING`. `StuckIndexingRecovery` (un `ApplicationRunner`) le passe à `FAILED` avec le message « indexation interrompue, réimporter ». C'est simple et honnête. Réessayer automatiquement serait une amélioration post-MVP.
- **Suppression** (AC4.2) : `DELETE /api/documents/{id}` → suppression des vecteurs, puis de la ligne, puis du fichier. Pendant une indexation (`INDEXING`), la suppression est refusée avec `409` pour éviter une course.

**Écarté :** une file de messages (RabbitMQ, Kafka). C'est justifié pour des milliers de documents ou plusieurs instances du backend. Ici, `@Async` suffit, et c'est la SPEC qui fixe ce volume.

### 2.6 Question → réponse : recherche explicite, puis génération en streaming

```
POST /api/chat { conversationId?, question }
  │
  ├─ ConversationService : charge (et vérifie le propriétaire) ou crée la conversation
  ├─ RetrievalService : vectorStore.similaritySearch(
  │        SearchRequest.builder().query(question).topK(4).similarityThreshold(0.55).build())
  │
  ├─ résultats vides ?  ──▶ OUI : event token("Je ne trouve pas cette information…")
  │                              + persistance des 2 messages + event done
  │                              (le ChatModel n'est JAMAIS appelé : AC8.1)
  │
  └─ NON :
       ├─ RagPromptFactory : system = règles + contexte numéroté [1]…[4]
       ├─ historique = N derniers messages de la conversation (history-window)
       ├─ chatClient.prompt().system(sys).messages(historique).user(question).stream().content()
       │      → Flux<String> : chaque fragment devient un event « token »
       ├─ à la fin du flux : event « sources » (documents trouvés, extraits ≤ 300 car., scores)
       ├─ persistance : message USER + message ASSISTANT (texte complet + sources en jsonb)
       └─ event « done » { conversationId, messageId }
```

Extrait du prompt système (`RagPromptFactory`) :

```
Tu es l'assistant interne d'Acme. Réponds en français, de façon concise.
Utilise UNIQUEMENT les extraits fournis ci-dessous. Si la réponse n'y figure pas,
réponds exactement : « Je ne trouve pas cette information dans les documents disponibles. »
Ne cite jamais de connaissances extérieures.

Extraits :
[1] (politique-teletravail.pdf, p. 2) …
[2] …
```

**Quoi :** effectuer la recherche vectorielle **nous-mêmes**, puis appeler le LLM, au lieu d'utiliser l'advisor `QuestionAnswerAdvisor` de Spring AI.
**Pourquoi :** l'advisor fait la recherche **à l'intérieur** de l'appel au LLM, ce qui est pratique pour un prototype. Or la SPEC exige deux choses qu'il rend difficiles :
1. **Refuser sans appeler le LLM** quand rien n'est pertinent (AC8.1). Il faut connaître le résultat de la recherche *avant* de décider d'appeler le modèle.
2. **Émettre les sources comme un événement SSE séparé** (AC6.1). Nous avons besoin de la liste des documents, avec leurs scores, entre nos mains.

Bonus : chaque étape devient une méthode testable isolément. `RetrievalService` se teste sans LLM, et `RagPromptFactory` se teste sans base.
**Écarté :** l'advisor. Il reste une excellente option pour un chatbot RAG sans exigence de refus. C'est une bonne réponse à donner en entretien : « je connais l'advisor, et voici pourquoi je ne l'ai pas utilisé ».

**Historique : nos propres tables plutôt que `JdbcChatMemoryRepository`.**
La mémoire JDBC de Spring AI stocke des messages par `conversationId`, mais **sans propriétaire, sans titre et sans sources**. Il nous faut ces trois informations (AC7.3, liste des conversations, citations réaffichées après rechargement). Nous aurions donc dû dupliquer les données. Avec nos tables `conversation` et `message`, il n'y a **qu'une seule source de vérité**, et l'historique est injecté dans le prompt via `.messages(...)`. *La SPEC § 3 est mise à jour en conséquence.*

**Titre de conversation :** les 60 premiers caractères de la première question. Demander un titre au LLM coûterait un appel de plus (lent avec Ollama) pour un gain faible.

### 2.7 Streaming SSE avec Spring MVC

```java
@PostMapping(value = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<Object>> chat(@Valid @RequestBody ChatRequest req,
                                          @AuthenticationPrincipal Jwt jwt) {
    return chatService.ask(jwt.getSubject(), req);
}
```

**Quoi :** rester sur **Spring MVC** (Tomcat, un thread par requête) tout en renvoyant un `Flux` de Reactor.
**Pourquoi :** Spring MVC sait nativement transformer un `Flux` en flux SSE. `ChatClient.stream()` renvoie déjà un `Flux<String>`. On garde ainsi le modèle de programmation « classique » (JPA bloquant, `@Transactional`, Spring Security servlet), qui est le plus répandu en entreprise et le plus simple à apprendre.
**Écarté :** WebFlux, qui est entièrement réactif. Il faudrait R2DBC à la place de JPA et une configuration de sécurité différente. C'est beaucoup de concepts nouveaux, pour un gain nul avec quelques utilisateurs.

**Format des événements** (le contrat avec le front) :

| `event:` | `data:` | Quand |
|---|---|---|
| `token` | `{"text":"Les employés "}` | Plusieurs fois, au fil de la génération |
| `sources` | `[{"documentId","fileName","page","excerpt","score"}]` | Une fois, après le dernier token (absent si refus) |
| `done` | `{"conversationId","messageId"}` | Toujours en dernier |
| `error` | `{"message":"Le modèle ne répond pas"}` | En cas d'échec en cours de flux |

> ⚠️ **Piège n° 1 : sécurité et dispatch asynchrone.** Quand un contrôleur MVC renvoie un `Flux`, Tomcat termine la requête dans un **dispatch `ASYNC`**. Sans la ligne `.dispatcherTypeMatchers(ASYNC, ERROR).permitAll()`, Spring Security réévalue l'autorisation sur ce dispatch, où le contexte d'authentification n'est plus présent. Le stream s'interrompt alors avec une `AccessDeniedException`. Ce n'est **pas** une faille : la requête initiale a déjà été authentifiée.
>
> ⚠️ **Piège n° 2 : transactions et streaming.** N'annotez pas la méthode qui renvoie le `Flux` avec `@Transactional`, car la transaction se fermerait avant que le flux ne produise quoi que ce soit. Persistez les messages dans une méthode de service séparée, transactionnelle, appelée dans `doOnComplete` ou `concatWith`.

### 2.8 Gestion des erreurs : `ProblemDetail`

`GlobalExceptionHandler` (`@RestControllerAdvice`) traduit les exceptions métier en réponses JSON au format **RFC 9457** (`ProblemDetail`, natif dans Spring) :

| Exception | Statut |
|---|---|
| `InvalidFileException` (type, extension) | 400 |
| `MaxUploadSizeExceededException` (levée par Spring au-delà de 20 Mo) | **400** (Spring renvoie 413 par défaut, la SPEC exige 400) |
| `DuplicateDocumentException` | 409 |
| `DocumentBusyException` (suppression en cours d'indexation) | 409 |
| `NotFoundException` (y compris la conversation d'un autre utilisateur) | 404 |
| `MethodArgumentNotValidException` (`@Valid`) | 400, avec le détail des champs |

**Pourquoi 404 et pas 403 pour la conversation d'autrui ?** Un `403` confirmerait que la conversation **existe**. Un `404` ne révèle rien : c'est une bonne pratique de sécurité, dite « énumération d'identifiants ».

---

## 3. API REST

| Méthode | Route | Rôle | Corps / réponse | AC |
|---|---|---|---|---|
| POST | `/api/auth/login` | public | `{email, password}` → `200 {token, role, expiresAt}` / `401` | AC1.1–1.3 |
| GET | `/api/me` | connecté | `200 {email, role}` | — |
| POST | `/api/documents` | ADMIN | multipart `file` → `202 {id, status}` / `400` / `409` | AC2.x |
| GET | `/api/documents` | ADMIN | `200 [DocumentDto]` trié par date décroissante | AC4.1 |
| GET | `/api/documents/{id}` | ADMIN | `200 DocumentDto` / `404` | — |
| DELETE | `/api/documents/{id}` | ADMIN | `204` / `404` / `409` si `INDEXING` | AC4.2–4.3 |
| POST | `/api/chat` | connecté | `{conversationId?, question}` (1–1000 car.) → `text/event-stream` | AC5.x, 6.x, 8.x |
| GET | `/api/conversations` | connecté | `200 [{id, title, updatedAt}]` de l'utilisateur courant | AC7.1 |
| GET | `/api/conversations/{id}` | propriétaire | `200 {id, title, messages:[{role, content, sources, createdAt}]}` / `404` | AC7.3–7.4 |
| GET | `/actuator/health` | public | `{status: UP}` | NF4 |

`DocumentDto = { id, fileName, contentType, sizeBytes, status, errorMessage, chunkCount, createdAt }`

**Pourquoi des DTO plutôt que renvoyer directement les entités JPA ?** L'entité `User` contient `passwordHash`, qu'on ne veut jamais sérialiser par accident. Les DTO (des `record` Java) figent le **contrat** de l'API : vous pouvez renommer une colonne en base sans casser le front. Ils évitent aussi les erreurs de chargement paresseux (`LazyInitializationException`) lors de la sérialisation JSON.

---

## 4. Schéma de base de données

**Quoi :** le schéma est créé par des **migrations Flyway** (`backend/src/main/resources/db/migration/V1__…sql`), et Hibernate est en mode `ddl-auto: validate`.
**Pourquoi :** chaque changement de schéma est un fichier SQL versionné et relu comme du code. Au démarrage, Flyway applique les migrations manquantes, dans l'ordre, sur n'importe quel environnement (votre poste, Testcontainers, docker compose). `validate` fait vérifier par Hibernate que les entités correspondent aux tables, **sans jamais les modifier**.
**Écarté :** `ddl-auto: update`. C'est pratique le premier jour, mais cela peut produire des schémas différents d'une machine à l'autre, ne supprime jamais rien et reste imprévisible.

> Règle d'or : **ne modifiez jamais une migration déjà appliquée.** Pour corriger, créez `V5__fix_….sql`.

```sql
-- V1__users.sql
CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt, jamais le mot de passe
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN','USER')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- V2__documents.sql
CREATE TABLE document (
    id            UUID PRIMARY KEY,
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL UNIQUE,   -- détection des doublons (AC2.5)
    storage_path  VARCHAR(500) NOT NULL,
    status        VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','INDEXING','INDEXED','FAILED')),
    error_message TEXT,
    chunk_count   INT          NOT NULL DEFAULT 0,
    uploaded_by   UUID         NOT NULL REFERENCES app_user(id),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- V3__vector_store.sql  (table utilisée par PgVectorStore de Spring AI)
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE vector_store (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,               -- { documentId, fileName, page }
    embedding VECTOR(768)          -- dimension de nomic-embed-text
);
CREATE INDEX vector_store_embedding_idx ON vector_store USING hnsw (embedding vector_cosine_ops);
CREATE INDEX vector_store_document_idx  ON vector_store ((metadata->>'documentId'));

-- V4__conversations.sql
CREATE TABLE conversation (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES app_user(id),
    title      VARCHAR(120) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX conversation_user_idx ON conversation (user_id, updated_at DESC);

CREATE TABLE message (
    id              UUID PRIMARY KEY,
    conversation_id UUID        NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    content         TEXT        NOT NULL,
    sources         JSONB,                        -- citations affichées après rechargement
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX message_conversation_idx ON message (conversation_id, created_at);
```

Les choix du schéma :

- **`vector_store` créée par nous** (`initialize-schema: false`). Sinon, Spring AI la crée au démarrage, hors de Flyway. En la plaçant dans une migration, on peut aussi ajouter l'index sur `documentId`, qui rend rapides la suppression et le `COUNT` (AC4.2). **Vérifiez à J4 que les colonnes correspondent au schéma attendu par `PgVectorStore` en 2.0.**
- **HNSW + `vector_cosine_ops`.** HNSW est un index de recherche *approximative* des plus proches voisins, très rapide. La distance cosinus mesure l'**angle** entre deux vecteurs, ce qui est la norme pour les embeddings de texte. **Le score renvoyé par Spring AI est une similarité** (1 = identique) : c'est ce score que compare `similarity-threshold`.
- **768 dimensions** : dimension imposée par `nomic-embed-text`. Changer de modèle d'embedding impose une nouvelle migration **et** une réindexation complète.
- **UUID** plutôt que `BIGSERIAL` : générés côté Java (`@Id @GeneratedValue(strategy = UUID)`), non devinables dans les URL, et identiques dans `document.id` et `metadata.documentId`.
- **Énumérations en `VARCHAR` + `CHECK`**, mappées en Java par `@Enumerated(EnumType.STRING)`. Ne **jamais** utiliser `ORDINAL` (stockage de 0, 1, 2…) : insérer une valeur au milieu de l'enum corromprait silencieusement toutes les données.
- **`sources` en `JSONB`** : ce sont des données en lecture seule, toujours affichées avec le message. Une table séparée n'apporterait rien. On la mappe avec `@JdbcTypeCode(SqlTypes.JSON)` sur un `List<SourceDto>`.
- **`ON DELETE CASCADE`** sur `message` : supprimer une conversation supprime ses messages, et c'est la base qui le garantit.

```
app_user 1───* document
app_user 1───* conversation 1───* message
document 1┄┄┄* vector_store   (lien logique via metadata->>'documentId', pas de FK)
```

---

## 5. Frontend

### 5.1 Stack

| Outil | Rôle | Pourquoi |
|---|---|---|
| **Vite** | Build + serveur de dev | Démarrage instantané, standard actuel (Create React App est abandonné) |
| **React Router** | Routes `/login`, `/chat/:id?`, `/documents` | Standard de fait |
| **TanStack Query** | Appels REST, cache, **polling** | `refetchInterval` rafraîchit les statuts d'indexation sans code maison (AC9.2), et gère les états loading/erreur |
| **@microsoft/fetch-event-source** | Lecture du flux SSE | Voir ci-dessous |
| **Tailwind CSS** | Styles | Rapide à écrire et cohérent, sans fichier CSS à organiser |
| **Vitest + React Testing Library + MSW** | Tests de composants | MSW intercepte les appels réseau : on teste le composant contre une fausse API |
| **Playwright** | Tests E2E | Pilote un vrai navigateur contre la stack docker |

**Pourquoi pas `EventSource`, l'API SSE native du navigateur ?** Elle ne permet **ni `POST`, ni en-tête `Authorization`**. Or notre route de chat reçoit un corps JSON et exige le JWT. `fetch-event-source` utilise `fetch` et parse le format SSE.

**Pourquoi pas Redux ?** L'état « serveur » (documents, conversations) est géré par TanStack Query. Le seul état global client est l'utilisateur connecté, qu'un `AuthContext` suffit à porter.

### 5.2 Arborescence

```
frontend/src/
├── main.tsx
├── app/
│   ├── App.tsx               # QueryClientProvider, AuthProvider, Router
│   ├── router.tsx
│   └── Layout.tsx            # barre de navigation (lien Documents si ADMIN)
├── api/
│   ├── client.ts             # fetch + Bearer + 401 → logout
│   ├── types.ts              # types miroirs des DTO backend
│   ├── documents.ts          # listDocuments, uploadDocument, deleteDocument
│   ├── conversations.ts
│   └── chatStream.ts         # POST /api/chat en SSE → callbacks onToken/onSources/onDone/onError
├── features/
│   ├── auth/      AuthContext.tsx, LoginPage.tsx, RequireAuth.tsx, RequireRole.tsx
│   ├── chat/      ChatPage.tsx, ConversationList.tsx, MessageList.tsx, MessageBubble.tsx,
│   │              SourcesPanel.tsx, ChatInput.tsx, useChatStream.ts
│   └── documents/ DocumentsPage.tsx, UploadDropzone.tsx, DocumentsTable.tsx, StatusBadge.tsx
├── components/    Button.tsx, Spinner.tsx, ErrorBanner.tsx
└── test/          setup.ts, handlers.ts (MSW)
frontend/e2e/      login.spec.ts, documents.spec.ts, chat.spec.ts
```

Même logique que le backend : **découpage par fonctionnalité**. `api/` est la seule couche qui connaît les URL ; les composants appellent des fonctions typées.

### 5.3 Authentification côté front

- Le JWT est stocké en **`sessionStorage`**. Il survit à un rechargement (AC7.4) et disparaît à la fermeture de l'onglet.
- **Compromis assumé :** tout stockage lisible en JavaScript est exposé en cas de faille XSS. L'alternative robuste (cookie `HttpOnly` + protection CSRF) complexifie le backend et sort du MVP. React échappe le HTML par défaut, et on s'interdit `dangerouslySetInnerHTML`. **C'est un point à mentionner spontanément en entretien.**
- `RequireRole role="ADMIN"` redirige un USER qui tape `/documents` à la main (AC9.1). **Ce n'est que du confort d'interface** : la vraie protection est le `403` du backend.
- `client.ts` : sur toute réponse `401`, il vide le token et redirige vers `/login` (token expiré).

### 5.4 Dev et production

- **Dev** : `vite.config.ts` → `server.proxy['/api'] = 'http://localhost:8080'`. Le front (port 5173) appelle `/api/...` en relatif, sans CORS.
- **Production** : `nginx.conf` sert `dist/` et relaie `/api/` vers `backend:8080`, avec **`proxy_buffering off;`** sur `/api/chat`.

> ⚠️ **Piège n° 3 : le buffering de nginx.** Par défaut, nginx accumule la réponse du backend avant de la transmettre. Le streaming fonctionne alors en dev, mais arrive **d'un seul bloc** en production. Il faut `proxy_buffering off;`, `proxy_http_version 1.1;` et un `proxy_read_timeout` long (120 s).

---

## 6. Stratégie de test

| Niveau | Outils | Ce qu'on teste | Vitesse |
|---|---|---|---|
| Unitaire | JUnit 5, Mockito, AssertJ | `FileValidator`, `RagPromptFactory`, `TokenService`, logique de refus | ms |
| Slice web | `@WebMvcTest` + `spring-security-test` | Règles d'accès 401/403, validation `@Valid`, mapping des erreurs | ~1 s |
| Intégration | `@SpringBootTest` + **Testcontainers** (`pgvector/pgvector:pg16`) + `@ServiceConnection` | Flyway, JPA, pgvector, flux complets import → indexation → chat | ~10 s au premier démarrage |
| Évaluation RAG | Profil Maven `eval`, **vrai Ollama** | Qualité de la recherche et des réponses (AC-EV) | minutes, **hors CI** |
| Front unitaire | Vitest, RTL, MSW | Composants, `useChatStream` | ms |
| E2E | Playwright | Parcours réels dans le navigateur | ~1 min, en local |

**Pourquoi Testcontainers ?** H2 (une base en mémoire) **ne connaît pas pgvector ni JSONB**. Les tests passeraient sur H2 et échoueraient en production. Testcontainers démarre un **vrai PostgreSQL dans Docker** pour la durée des tests. Avec `@ServiceConnection`, Spring Boot configure automatiquement la datasource vers ce conteneur, sans URL à écrire.

**Comment tester du RAG sans LLM ?** Une `@TestConfiguration` remplace les beans d'IA :

- **`FakeEmbeddingModel`** : transforme un texte en vecteur 768 de façon **déterministe**. Chaque mot normalisé est haché vers une dimension, qu'on incrémente, puis le vecteur est normalisé. Deux textes qui partagent des mots ont donc une similarité élevée, et deux textes sans rapport une similarité proche de 0. Cela permet de tester pour de vrai la recherche, le seuil et le refus (« capitale du Pérou » ne ressemble à aucun document Acme), en quelques millisecondes et sans Ollama.
- **`ChatModel` mocké** (Mockito) : renvoie `Flux.just("Deux ", "jours ", "par semaine.")`. On vérifie le nombre d'événements `token` (AC5.1), on **capture le prompt reçu** pour vérifier qu'il contient l'historique (AC7.2), et on vérifie `verifyNoInteractions(chatModel)` en cas de refus (AC8.1).

**Asynchrone :** l'indexation se fait dans un autre thread. On attend donc avec **Awaitility**, jamais avec `Thread.sleep` :
`await().atMost(10, SECONDS).until(() -> repo.findById(id).get().getStatus() == INDEXED);`

**Qualité du code :** Spotless (google-java-format) côté Java, ESLint + Prettier côté TypeScript. Le formatage n'est jamais discuté en revue : la CI le vérifie.

---

## 7. Docker

### 7.1 `docker-compose.yml` (stack complète)

| Service | Image | Détails |
|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | volume `pgdata`, `healthcheck: pg_isready` |
| `ollama` | `ollama/ollama` | volume `ollama` (les modèles pèsent environ 5 Go et ne sont téléchargés qu'une fois) |
| `ollama-init` | `ollama/ollama` | conteneur **éphémère** : `ollama pull llama3.1:8b && ollama pull nomic-embed-text`, puis se termine |
| `backend` | build `backend/Dockerfile` | `depends_on: postgres (service_healthy), ollama-init (service_completed_successfully)`, volume `uploads` |
| `frontend` | build `frontend/Dockerfile` | nginx, port `8080:80` exposé |

**Pourquoi `ollama-init` ?** Le téléchargement des modèles est long et ne doit arriver qu'une fois. Un conteneur dédié qui se termine avec succès est la façon idiomatique d'exprimer « le backend ne démarre qu'une fois les modèles disponibles ».

**Dockerfile backend multi-stage :** une étape `maven:…-temurin-21` compile le jar, puis une étape `eclipse-temurin:21-jre` ne contient que le JRE et le jar. L'image finale ne contient ni Maven, ni les sources, ni le cache de dépendances : elle est plus petite et expose moins de surface d'attaque. Même principe pour le front : `node` pour `npm run build`, puis `nginx:alpine` avec `dist/`.

**`.env.example`** (commité) → `.env` (ignoré par git) : `JWT_SECRET`, `ADMIN_PASSWORD`, `USER_PASSWORD`, `DB_PASSWORD`.

### 7.2 Mode développement

`docker compose -f docker-compose.dev.yml up -d` ne lance que `postgres` et `ollama`. Le backend tourne dans l'IDE (`./mvnw spring-boot:run`, avec débogueur et rechargement), et le front avec `npm run dev`. C'est le mode de travail quotidien, du jalon J1 à J9.

---

## 8. Jalons

**Règles communes à tous les jalons :**
- On travaille sur une branche `feat/jN-…`, puis PR vers `main` (même en solo : l'historique de PR est visible sur GitHub).
- **Définition de « terminé » :** `./mvnw verify` et `npm run lint && npm test` sont verts, la CI est verte, et les AC du jalon sont couverts par un test automatisé (sauf ceux marqués M dans la SPEC).
- La durée est une estimation pour un développeur junior, recherche documentaire comprise.

### Correspondance avec les jalons de la SPEC

| SPEC | Jalons de ce plan |
|---|---|
| M1 Socle et authentification | J1, J2 |
| M2 Ingestion | J3, J4 |
| M3 Chat, citations et streaming | J5, J6 |
| M4 Historique | J7 |
| M5 Frontend | J8, J9 |
| M6 Évaluation, CI et README | J10, J11, J12 |

---

### J1 : Squelette et CI · 1 jour

**Objectif :** un backend vide qui démarre, se connecte à Postgres et se teste en CI.

**Livrables**
- `backend/` généré sur start.spring.io (Java 21, Maven, Boot 4.1 ; starters webmvc, security, data-jpa, flyway, actuator, validation, testcontainers), plus le BOM Spring AI 2.0.
- `docker-compose.dev.yml` (postgres + ollama) ; `V0__init.sql` minimal (`CREATE EXTENSION vector`).
- `application.yml` avec variables d'environnement ; `.gitignore` ; `.env.example`.
- Premier test : `@SpringBootTest` + Testcontainers → `contextLoads()`.
- `.github/workflows/ci.yml` : job `backend` (setup-java temurin 21, cache Maven, `./mvnw -B verify`).
- Spotless configuré.

**Concepts Spring :** starter, auto-configuration, `application.yml` et profils, `@SpringBootTest`, `@ServiceConnection`.

**Validation**
```bash
docker compose -f docker-compose.dev.yml up -d
cd backend && ./mvnw verify                         # contextLoads vert
./mvnw spring-boot:run &
curl -s localhost:8080/actuator/health              # {"status":"UP"}
```
- ✅ Le badge CI est vert sur `main`.

**Piège :** tant que Spring Security n'est pas configuré, il protège **tout** avec un mot de passe généré, affiché dans les logs. Autorisez `/actuator/health` dès maintenant.

---

### J2 : Authentification JWT · 1,5 à 2 jours

**Objectif :** se connecter et obtenir un JWT ; les routes sont protégées par rôle.

**Livrables**
- `V1__users.sql`, entité `User`, `Role`, `UserRepository`.
- `SecurityConfig` (resource server, `JwtEncoder`/`JwtDecoder` HS256, `PasswordEncoder` BCrypt, `AuthenticationManager`).
- `TokenService`, `AuthController` (`POST /api/auth/login`, `GET /api/me`), `DataSeeder`.
- `GlobalExceptionHandler` (base `ProblemDetail`).
- Un contrôleur factice `GET /api/documents` qui renvoie `[]`, pour tester le `403` dès maintenant.

**Concepts Spring :** chaîne de filtres de sécurité, `SecurityFilterChain`, `UserDetailsService`, `@AuthenticationPrincipal`, `ApplicationRunner`, `@RestControllerAdvice`.

**Validation**
- ✅ **AC1.1** : login `user@acme.local` → `200` + JWT contenant `roles: ["USER"]` (test d'intégration).
- ✅ **AC1.2** : mauvais mot de passe → `401`, message identique pour un email inconnu.
- ✅ **AC1.3** : sans token, token expiré (TTL de test = 1 s) ou signature altérée → `401`.
- ✅ **AC1.4** : le hash en base commence par `$2a$` ou `$2b$` et diffère du mot de passe (test unitaire).
- ✅ `GET /api/documents` avec un token USER → `403`, avec un token ADMIN → `200` (`@WebMvcTest`).
```bash
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@acme.local","password":"…"}' | jq -r .token)
curl -s localhost:8080/api/me -H "Authorization: Bearer $TOKEN"
```

**Piège :** le secret HS256 doit faire **au moins 32 octets**, sinon Nimbus refuse de signer. En test, fournissez-en un dans `src/test/resources/application-test.yml`.

---

### J3 : Import et gestion des documents (sans indexation) · 1,5 jour

**Objectif :** l'admin importe, liste et supprime des fichiers ; toutes les validations sont en place.

**Livrables**
- `V2__documents.sql`, `Document`, `DocumentStatus`, `DocumentRepository`.
- `FileValidator` (Tika), `FileStorage`, `DocumentService`, `DocumentController`, `DocumentDto`.
- `DocumentUploadedEvent` publié (aucun écouteur pour l'instant).
- Mapping `MaxUploadSizeExceededException` → 400, `DuplicateDocumentException` → 409.
- Fichiers de test dans `src/test/resources/files/` : petit PDF, docx, md, faux PDF (un exe renommé).

**Concepts Spring :** `MultipartFile`, `@Transactional`, `ApplicationEventPublisher`, `ResponseEntity.accepted()`.

**Validation**
- ✅ **AC2.1** : USER → `403`.
- ✅ **AC2.2** : les 4 formats valides → `202` + `PENDING`, et le fichier existe sur le disque.
- ✅ **AC2.3** : fichier de 21 Mo → `400` avec un `detail` explicite.
- ✅ **AC2.4** : `.exe` et `.exe` renommé en `.pdf` → `400`.
- ✅ **AC2.5** : second import identique → `409`.
- ✅ **AC4.1** : la liste contient les documents avec leur statut ; **AC4.3** : supprimer un id inconnu → `404`.

**Piège :** pour un fichier `.md` ou `.txt`, Tika détecte `text/plain`. La table des types acceptés doit associer **extension + type détecté** (`md` → `text/plain` ou `text/markdown`).

---

### J4 : Ingestion vers pgvector · 2 jours

**Objectif :** un document importé devient des vecteurs interrogeables.

**Livrables**
- Dépendances Spring AI (Ollama, pgvector, lecteurs PDF et Tika) ; `V3__vector_store.sql`.
- `AsyncConfig` (`@EnableAsync`, pool borné), `IngestionListener`, `IngestionService`, `DocumentReaderFactory`, `StuckIndexingRecovery`.
- Suppression d'un document : vecteurs, puis ligne, puis fichier ; `409` si `INDEXING`.
- `FakeEmbeddingModel` dans une `@TestConfiguration` partagée (`TestAiConfig`).
- `RagProperties` (chunk-size, overlap).

**Concepts Spring :** `@Async`, `@TransactionalEventListener`, `ThreadPoolTaskExecutor`, `@ConfigurationProperties`, beans `@Primary` en test.

**Validation**
- ✅ **AC3.1** : PDF importé → `INDEXED` en moins de 60 s (Awaitility), `chunk_count > 0`, et `SELECT count(*) FROM vector_store WHERE metadata->>'documentId' = ?` égal à `chunk_count`.
- ✅ **AC3.2** : PDF corrompu → `FAILED`, `error_message` non vide, 0 vecteur.
- ✅ **AC3.3** : les chunks d'un PDF de 3 pages ont `metadata.page` ∈ {1, 2, 3} (test unitaire du lecteur).
- ✅ **AC4.2** : suppression → `204`, et le compte de vecteurs vaut 0.
- ✅ Vérification manuelle avec le vrai Ollama :
```bash
docker exec -it <postgres> psql -U rag -c \
 "select metadata->>'fileName', metadata->>'page', left(content,60) from vector_store limit 5;"
```

**Piège :** dans un test d'intégration, la transaction du test lui-même ne se valide jamais (rollback), donc `AFTER_COMMIT` ne se déclenche pas. **N'annotez pas ces tests `@Transactional`**, et nettoyez les tables dans un `@AfterEach`.

---

### J5 : Recherche, refus et prompt · 1,5 jour

**Objectif :** la logique de réponse, sans streaming : on sait décider entre refuser et répondre, et construire le bon prompt.

**Livrables**
- `RetrievalService` (topK + seuil depuis `RagProperties`).
- `RagPromptFactory` : prompt système, contexte numéroté, extraits tronqués à 300 caractères pour les sources.
- `ChatService.answer(...)` en version **non streaming** (renvoie `String` + sources). C'est une étape intermédiaire pour valider la logique avant d'ajouter la complexité du flux.
- `ChatModel` mocké dans `TestAiConfig`.

**Concepts Spring :** `ChatClient.Builder` (bean auto-configuré), `SearchRequest`, `Document.getScore()`, `ArgumentCaptor` de Mockito.

**Validation**
- ✅ **AC8.1** : « Quelle est la capitale du Pérou ? » sur le corpus de test → message de refus exact, et `verifyNoInteractions(chatModel)`.
- ✅ **AC8.2** : seuil 0.99 → refus ; seuil 0.1 → réponse, pour la même question (test paramétré).
- ✅ **AC5.2** : le prompt système capturé contient « en français » et « UNIQUEMENT les extraits ».
- ✅ Test manuel avec le vrai Ollama, via un test `@Tag("manual")` ou un endpoint temporaire : une question sur le document de test donne une réponse cohérente.

**Piège :** le score dépend du modèle d'embedding. Le seuil calibré avec le `FakeEmbeddingModel` **n'est pas** celui de nomic-embed-text : les tests fixent leur propre seuil, et le vrai seuil se calibre à J11.

---

### J6 : Streaming SSE et citations · 1,5 jour

**Objectif :** `POST /api/chat` diffuse la réponse token par token, puis les sources.

**Livrables**
- `ChatController` renvoyant un `Flux<ServerSentEvent<?>>`.
- `ChatService.ask(...)` : refus → `token` + `done` ; sinon `token`…, `sources`, `done` ; `onErrorResume` → `error`.
- Règle `dispatcherTypeMatchers(ASYNC, ERROR)` dans `SecurityConfig`.
- À ce stade, `conversationId` est un simple UUID généré et non persisté (la persistance arrive à J7).

**Concepts Spring :** SSE, `Flux`, `ServerSentEvent.builder()`, `concatWith`, `onErrorResume`, `WebTestClient` ou `MockMvc` avec `asyncDispatch`.

**Validation**
- ✅ **AC5.1** : `Content-Type: text/event-stream`, au moins 2 événements `token`, et `done` en dernier (test d'intégration).
- ✅ **AC6.1** : l'événement `sources` contient au moins une entrée `{fileName, excerpt ≤ 300, score}` ; pas d'événement `sources` en cas de refus.
- ✅ Le `ChatModel` qui lève une exception produit un événement `error` sans casser la connexion.
- ✅ Démonstration manuelle :
```bash
curl -N -X POST localhost:8080/api/chat -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"question":"Combien de jours de télétravail ?"}'
# les lignes event:token apparaissent progressivement
```

**Piège :** voir les pièges n° 1 et n° 2 (§ 2.7).

---

### J7 : Conversations et historique · 1,5 jour

**Objectif :** les échanges sont persistés, reprenables et privés.

**Livrables**
- `V4__conversations.sql`, `Conversation`, `Message` (`sources` en JSONB), repositories.
- `ConversationService` : création (titre = 60 premiers caractères), chargement avec vérification du propriétaire, `lastMessages(n)`.
- `ChatService` : historique injecté dans le prompt, et persistance USER + ASSISTANT (refus compris) en fin de flux.
- `ConversationController` : liste et détail.

**Concepts Spring :** relations JPA (`@ManyToOne(fetch = LAZY)`), requêtes dérivées (`findTop6ByConversationIdOrderByCreatedAtDesc`), `@JdbcTypeCode(SqlTypes.JSON)`, projection DTO.

**Validation**
- ✅ **AC7.1** : une question sans `conversationId` → `done.conversationId` renvoyé, et la conversation apparaît dans `GET /api/conversations`.
- ✅ **AC7.2** : question de suivi → le prompt capturé contient la question **et** la réponse précédentes.
- ✅ **AC7.3** : l'utilisateur A qui lit, ou écrit dans, la conversation de B → `404`.
- ✅ `GET /api/conversations/{id}` renvoie les messages avec leurs sources.

**Piège :** `findTop6…Desc` renvoie les messages **du plus récent au plus ancien**. Il faut inverser la liste avant de l'envoyer au LLM, sinon le dialogue est présenté à l'envers.

---

### J8 : Frontend 1, socle et page Documents · 2 jours

**Objectif :** une interface pour se connecter et gérer les documents, avec la CI front.

**Livrables**
- `npm create vite@latest frontend -- --template react-ts`, puis Tailwind, React Router, TanStack Query, ESLint, Prettier, Vitest, MSW.
- `api/client.ts`, `AuthContext`, `LoginPage`, `RequireAuth`, `RequireRole`, `Layout`.
- `DocumentsPage` : `UploadDropzone` (glisser-déposer + input), `DocumentsTable` avec `StatusBadge`, polling toutes les 2 s **tant qu'un document est `PENDING` ou `INDEXING`**, suppression avec confirmation.
- Proxy Vite ; job CI `frontend` (`npm ci`, `lint`, `tsc --noEmit`, `test`, `build`).

**Validation**
- ✅ **AC9.1** : un USER ne voit pas « Documents », et `/documents` le redirige (test RTL, puis E2E à J12).
- ✅ **AC9.2** : démonstration manuelle : glisser un PDF, le badge passe de `PENDING` à `INDEXING` puis `INDEXED` sans recharger la page.
- ✅ **AC9.4** : la déconnexion vide `sessionStorage` et renvoie vers `/login` (test RTL).
- ✅ Un token expiré (401) renvoie vers `/login`.
- ✅ Le job CI `frontend` est vert.

**Piège :** pour l'upload, **ne fixez pas** l'en-tête `Content-Type` à la main avec `FormData`. Le navigateur doit générer lui-même la `boundary` du multipart.

---

### J9 : Frontend 2, chat · 2 jours

**Objectif :** l'expérience de chat complète.

**Livrables**
- `chatStream.ts` (fetch-event-source) et `useChatStream` (états `idle | streaming | error`, texte accumulé, sources, `conversationId`).
- `ChatPage` (`/chat/:id?`), `ConversationList` (barre latérale), `MessageList`, `MessageBubble`, `SourcesPanel` (dépliable : fichier, page, extrait, score en %), `ChatInput` (désactivé pendant le streaming, Entrée pour envoyer).
- Après `done` : navigation vers `/chat/{conversationId}` et invalidation de la requête `conversations`.

**Validation**
- ✅ **AC5.3** : démonstration : le texte s'affiche progressivement.
- ✅ **AC6.3** : les sources sont affichées sous la réponse et dépliables.
- ✅ **AC7.4** : après F5 sur `/chat/{id}`, les messages (et leurs sources) sont rechargés, et on peut continuer.
- ✅ **AC9.3** : tests Vitest de `useChatStream` et `MessageList` : affichage du chargement, accumulation des tokens, message d'erreur sur l'événement `error`.

**Piège :** `fetch-event-source` **retente automatiquement** en cas d'erreur, ce qui renverrait la question plusieurs fois. Dans `onerror`, relancez l'exception pour stopper les tentatives.

---

### J10 : Dockerisation complète · 1,5 jour

**Objectif :** `docker compose up` sur une machine propre suffit.

**Livrables**
- `backend/Dockerfile` et `frontend/Dockerfile` (multi-stage), `frontend/nginx.conf` (SPA fallback `try_files … /index.html`, proxy `/api`, buffering désactivé).
- `docker-compose.yml` complet (5 services, healthchecks, volumes), `.env.example` finalisé.
- `.dockerignore` (exclure `node_modules`, `target`).

**Validation**
- ✅ **AC-NF4** : sur un clone neuf, `cp .env.example .env && docker compose up --build` → `http://localhost:8080` accessible, les comptes seed se connectent, et une question reçoit une réponse **en streaming à travers nginx**.
- ✅ **AC-NF3** : `git grep -iE "secret|password" -- ':!*.example' ':!docs'` ne révèle aucune valeur réelle.
- ✅ Après `docker compose down` puis `up`, les documents et conversations sont conservés (volumes).

**Piège :** dans les conteneurs, `localhost` désigne le conteneur lui-même. Le backend doit viser `http://ollama:11434` et `jdbc:postgresql://postgres:5432/…`, c'est-à-dire le **nom du service**.

---

### J11 : Corpus de démo, évaluation et calibrage · 2 jours

**Objectif :** mesurer la qualité avec des chiffres et régler les paramètres.

**Livrables**
- `demo-data/` : les 7 documents Acme fictifs (SPEC § 9), rédigés avec des faits précis et vérifiables (« 2 jours de télétravail par semaine », « plafond repas 20 € »…).
- `eval/questions.yaml` : 12 questions couvertes et 3 pièges.
- `EvalRunner` (test JUnit `@Tag("eval")`, lancé via `./mvnw verify -Peval`) : il charge le corpus, attend l'indexation, pose chaque question et calcule le **hit rate@4**, le taux de mots-clés trouvés et le taux de refus. Il écrit `eval/report-<date>.md`.
- Calibrage de `similarity-threshold`, `chunk-size` et du modèle de chat, en consignant chaque essai dans le rapport.
- Mesure du premier token (NF1) sur 5 questions.

**Validation**
- ✅ **AC-EV1 / AC6.2** : hit rate@4 ≥ 80 % sur les 12 questions couvertes.
- ✅ **AC-EV2** : ≥ 70 % des réponses contiennent tous les mots-clés attendus.
- ✅ **AC-EV3 / AC8.3** : `./mvnw verify -Peval` produit le rapport, et les 3 pièges sont refusés.
- ✅ **AC-NF1** : premier token en moins de 10 s sur 5 questions consécutives (mesuré et noté dans le rapport, avec la configuration de la machine).

**Pourquoi un rapport versionné ?** Il montre une **démarche d'ingénieur** : « avec des chunks de 800 j'avais 75 %, avec 500 et un chevauchement de 100 j'ai 88 % ». C'est l'élément le plus différenciant du portfolio.

**Piège :** si le seuil est trop haut, les vraies questions sont refusées ; s'il est trop bas, les pièges passent. Tracez les scores obtenus sur les deux groupes de questions et placez le seuil **entre** les deux distributions.

---

### J12 : E2E, CI finale et README · 1,5 jour

**Objectif :** le projet est présentable.

**Livrables**
- Playwright : `login.spec.ts`, `documents.spec.ts` (import → `INDEXED`, USER redirigé), `chat.spec.ts` (question → réponse + sources → rechargement). Lancés en local contre `docker compose`.
- CI finale : jobs `backend`, `frontend` et `docker-build` (vérifie que les images se construisent).
- `README.md` : pitch, GIF de démo, schéma d'architecture, démarrage rapide, résultats d'évaluation, choix techniques (lien vers ce PLAN), limites et pistes (reprise des éléments hors périmètre).

**Validation**
- ✅ Tous les AC marqués **(E)** dans la SPEC passent : 5.3, 6.3, 7.4, 9.1, 9.2, 9.4.
- ✅ **AC-NF6** : CI verte, et un commit volontairement cassé (test ou lint) la fait passer au rouge.
- ✅ Relecture du README par une tierce personne : elle lance le projet sans aide.

**Pourquoi Playwright hors CI ?** Les tests E2E réels nécessitent Ollama (environ 5 Go de modèles, lent sans GPU), ce qui est trop coûteux sur un runner GitHub gratuit. *Amélioration possible :* un profil Spring `fake-ai` qui charge le `FakeEmbeddingModel` et un `ChatModel` de démonstration, pour exécuter les E2E en CI.

---

### Récapitulatif

| Jalon | Durée | AC couverts |
|---|---|---|
| J1 Squelette | 1 j | NF6 (partiel) |
| J2 Authentification | 1,5–2 j | 1.1–1.4 |
| J3 Import | 1,5 j | 2.1–2.5, 4.1, 4.3 |
| J4 Ingestion | 2 j | 3.1–3.3, 4.2 |
| J5 Retrieval et refus | 1,5 j | 5.2, 8.1, 8.2 |
| J6 Streaming | 1,5 j | 5.1, 6.1 |
| J7 Historique | 1,5 j | 7.1–7.3 |
| J8 Front Documents | 2 j | 9.1, 9.2, 9.4 |
| J9 Front Chat | 2 j | 5.3, 6.3, 7.4, 9.3 |
| J10 Docker | 1,5 j | NF3, NF4 |
| J11 Évaluation | 2 j | EV1–EV3, 6.2, 8.3, NF1 |
| J12 E2E et README | 1,5 j | NF6, les AC E en revue finale |
| **Total** | **≈ 20 jours** | **tous les AC de la SPEC** |

---

## 9. Petit glossaire Spring

| Terme | En une phrase |
|---|---|
| **Bean** | Objet créé et géré par Spring, injecté là où on le demande. |
| **Injection par constructeur** | Les dépendances d'une classe sont passées à son constructeur ; Spring s'en charge. |
| **Starter** | Dépendance « paquet » qui apporte des bibliothèques cohérentes et leur configuration automatique. |
| **Auto-configuration** | Code de Spring Boot qui crée des beans par défaut selon le classpath et les propriétés. |
| **Profil** | Jeu de configuration activable (`dev`, `test`, `eval`) via `spring.profiles.active`. |
| **`@Transactional`** | La méthode s'exécute dans une transaction : tout est validé, ou tout est annulé. Ne fonctionne que sur un appel **venant d'un autre bean** (proxy). |
| **Entité / Repository** | Classe Java mappée sur une table / interface dont Spring Data génère l'implémentation. |
| **DTO** | Objet simple qui définit ce qui entre ou sort de l'API, distinct de l'entité. |
| **Slice test** | Test qui ne démarre qu'une tranche de l'application (`@WebMvcTest` : couche web seulement). |
| **`@ServiceConnection`** | Relie automatiquement un conteneur Testcontainers à la configuration Spring. |
| **SecurityFilterChain** | Suite de filtres HTTP qui authentifient et autorisent chaque requête avant le contrôleur. |
| **Embedding** | Vecteur de nombres qui représente le sens d'un texte ; deux textes proches ont des vecteurs proches. |
| **Chunk** | Morceau de document (environ 800 tokens) indexé séparément, pour que la recherche renvoie un passage précis. |
| **SSE** | Server-Sent Events : réponse HTTP qui reste ouverte et envoie des événements texte au fil de l'eau. |
