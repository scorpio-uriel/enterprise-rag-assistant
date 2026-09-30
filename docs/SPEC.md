# Enterprise RAG Assistant — Spécification MVP

> Statut : validée avant développement · Date : 2026-09-30

## 1. Objectif

Un assistant interne qui répond aux questions des employés d'une entreprise **à partir de ses documents** (politiques RH, procédures, guides IT) en citant ses sources. Si l'information n'est pas dans les documents, il le dit au lieu d'inventer.

**Objectif portfolio :** montrer une architecture RAG propre et testée de bout en bout : ingestion, retrieval, génération en streaming, sécurité par rôles, évaluation mesurable de la qualité, et démarrage en une commande.

## 2. Utilisateurs et rôles

| Rôle | Description | Permissions |
|------|-------------|-------------|
| `ADMIN` | Responsable de la base documentaire | Tout ce que fait `USER`, plus : importer, lister, consulter le statut et supprimer des documents |
| `USER` | Employé | Poser des questions, consulter et reprendre **ses propres** conversations |

Deux comptes de démo sont créés au démarrage : `admin@acme.local` et `user@acme.local`. Leurs mots de passe sont définis par variables d'environnement.

## 3. Stack et architecture

- **Backend :** Java 21, Spring Boot 3, Spring Security (JWT), Spring AI (`ChatClient`, advisor de RAG, `ChatMemory` JDBC), Spring Data JPA, Flyway
- **Base de données :** PostgreSQL + pgvector (`PgVectorStore`, index HNSW, distance cosinus)
- **LLM et embeddings :** Ollama en local, par exemple `llama3.1:8b` pour le chat et `nomic-embed-text` pour les embeddings
- **Frontend :** React + TypeScript + Vite
- **Exécution :** `docker compose` (postgres, ollama, backend, frontend)

```
Ingestion (asynchrone)
  Upload ─▶ validation ─▶ lecture (PDF / Tika) ─▶ découpage en chunks (TokenTextSplitter)
         ─▶ embeddings (Ollama) ─▶ pgvector (métadonnées : documentId, fileName, page)

Question
  Question ─▶ embedding ─▶ recherche top-K (seuil de similarité)
           ├─ aucun chunk au-dessus du seuil ─▶ message de refus (sans appel au LLM)
           └─ chunks trouvés ─▶ prompt (contexte + historique) ─▶ LLM ─▶ SSE (tokens + citations)
```

## 4. Périmètre MVP

| ID | Fonctionnalité | Détail |
|----|----------------|--------|
| F1 | Authentification | `POST /api/auth/login` (email + mot de passe) renvoie un JWT signé avec expiration. Mots de passe hachés en BCrypt. API stateless. Comptes seed. |
| F2 | Import de documents | ADMIN uniquement. Formats : PDF (texte, sans OCR), `.md`, `.txt`, `.docx`. Taille max : 20 Mo. Validation de l'extension **et** du type MIME réel. Un doublon (même hash SHA-256) est rejeté. |
| F3 | Indexation asynchrone | L'import répond immédiatement `202`. Le traitement se fait en tâche de fond : extraction du texte → découpage (taille et chevauchement configurables) → embeddings → pgvector. Statuts : `PENDING → INDEXING → INDEXED` ou `FAILED` (avec message d'erreur). |
| F4 | Gestion des documents | ADMIN : lister les documents (nom, type, taille, date, statut, nombre de chunks) et supprimer un document **et tous ses vecteurs**. |
| F5 | Chat en streaming | `POST /api/chat` renvoie un flux SSE de tokens. Réponse en français, fondée uniquement sur le contexte récupéré. |
| F6 | Citations | Chaque réponse se termine par un événement `sources` : nom du document, page (si disponible), extrait (≤ 300 caractères) et score de similarité. |
| F7 | Historique de conversation | Conversations persistées par utilisateur. Liste et reprise d'une conversation. Les questions de suivi utilisent les N derniers échanges (fenêtre configurable). |
| F8 | Refus hors corpus | Si aucun chunk ne dépasse le seuil de similarité configurable, l'assistant répond : « Je ne trouve pas cette information dans les documents disponibles. », sans appeler le LLM. |
| F9 | Frontend | Pages : Connexion · Chat (liste des conversations, zone de chat en streaming, sources dépliables) · Documents (réservée à l'ADMIN : import par glisser-déposer, tableau des statuts avec rafraîchissement, suppression). Les routes sont protégées selon le rôle. |

## 5. Hors périmètre (MVP)

- OCR des PDF scannés, images et tableaux complexes
- Import par URL, HTML, Confluence ou SharePoint
- Multi-tenant et permissions par document ou par équipe
- SSO, OAuth2/OIDC, Keycloak ; inscription et réinitialisation du mot de passe
- Fournisseurs LLM cloud (OpenAI, Claude…)
- Déploiement en ligne et démo publique
- Re-ranking, recherche hybride (BM25 + vecteurs), réécriture de requêtes
- Interface multilingue (i18n)
- Édition ou versionnage des documents
- Feedback utilisateur (👍/👎) sur les réponses
- Observabilité avancée (tracing, dashboards de métriques)

## 6. Exigences non fonctionnelles

| ID | Exigence |
|----|----------|
| NF1 | **Performance** : premier token en moins de 10 s, et indexation d'un PDF de 20 pages en moins de 60 s, sur un poste de développement (16 Go de RAM, sans GPU dédié). |
| NF2 | **Volumétrie** : environ 100 documents et quelques utilisateurs simultanés, sans dégradation fonctionnelle. |
| NF3 | **Sécurité** : BCrypt, JWT avec expiration (≤ 1 h), endpoints `/api/documents/**` réservés à l'ADMIN, isolation des conversations par utilisateur, aucun secret versionné (`.env.example` fourni). |
| NF4 | **Exploitabilité** : `docker compose up` suffit (téléchargement des modèles Ollama automatisé). Endpoint `/actuator/health`. |
| NF5 | **Configurabilité** : `topK`, seuil de similarité, taille et chevauchement des chunks, fenêtre d'historique et modèles sont externalisés dans `application.yml` ou des variables d'environnement. |
| NF6 | **Qualité** : CI verte sur chaque push. Les tests n'appellent jamais un vrai LLM (mocks ou stubs). |

## 7. API (esquisse)

| Méthode | Route | Rôle | Réponse |
|---------|-------|------|---------|
| POST | `/api/auth/login` | public | `200 { token, role }` / `401` |
| POST | `/api/documents` (multipart) | ADMIN | `202 { id, status: PENDING }` / `400` / `409` |
| GET | `/api/documents` | ADMIN | `200 [ { id, fileName, type, size, status, chunkCount, createdAt } ]` |
| DELETE | `/api/documents/{id}` | ADMIN | `204` / `404` |
| POST | `/api/chat` | USER, ADMIN | `text/event-stream` : événements `token`, `sources`, `done`, `error` ; corps `{ conversationId?, question }` |
| GET | `/api/conversations` | USER, ADMIN | `200` : conversations de l'utilisateur courant |
| GET | `/api/conversations/{id}` | propriétaire | `200` : messages / `404` si la conversation appartient à un autre utilisateur |

## 8. Critères d'acceptation

Format : **Étant donné / Quand / Alors**. Type de test : **U** = unitaire, **I** = intégration (Testcontainers pgvector, LLM mocké), **E** = E2E Playwright, **EV** = évaluation RAG, **M** = vérification manuelle.

### F1 — Authentification
- **AC1.1 (I)** Étant donné le compte seed `user@acme.local`, quand il se connecte avec le bon mot de passe, alors il reçoit `200` et un JWT contenant le rôle `USER`.
- **AC1.2 (I)** Quand le mot de passe est faux, alors la réponse est `401`, sans indiquer si l'email existe.
- **AC1.3 (I)** Quand une route protégée est appelée sans token, ou avec un token expiré ou altéré, alors la réponse est `401`.
- **AC1.4 (U)** Le mot de passe stocké en base est un hash BCrypt, jamais le texte en clair.

### F2 — Import
- **AC2.1 (I)** Étant donné un USER authentifié, quand il appelle `POST /api/documents`, alors la réponse est `403`.
- **AC2.2 (I)** Quand un ADMIN importe un PDF, un `.md`, un `.txt` ou un `.docx` valide, alors la réponse est `202` avec le statut `PENDING`.
- **AC2.3 (I)** Quand un ADMIN importe un fichier de plus de 20 Mo, alors la réponse est `400` avec un message explicite.
- **AC2.4 (I)** Quand un ADMIN importe un `.exe`, ou un `.exe` renommé en `.pdf`, alors la réponse est `400` (type MIME réel vérifié).
- **AC2.5 (I)** Quand un ADMIN importe deux fois le même fichier, alors le second import renvoie `409`.

### F3 — Indexation
- **AC3.1 (I)** Étant donné un PDF valide importé, alors en moins de 60 s son statut passe à `INDEXED`, `chunkCount > 0`, et pgvector contient `chunkCount` vecteurs avec `metadata.documentId` égal à l'id du document.
- **AC3.2 (I)** Étant donné un PDF corrompu, alors le statut passe à `FAILED` avec un message d'erreur non vide, et aucun vecteur n'est laissé en base.
- **AC3.3 (U)** Les chunks issus d'un PDF portent le numéro de page dans leurs métadonnées.

### F4 — Gestion des documents
- **AC4.1 (I)** `GET /api/documents` renvoie tous les documents avec leur statut courant.
- **AC4.2 (I)** Étant donné un document `INDEXED`, quand l'ADMIN le supprime, alors la réponse est `204`, le document n'apparaît plus dans la liste, et `COUNT` des vecteurs avec son `documentId` vaut 0.
- **AC4.3 (I)** Supprimer un id inexistant renvoie `404`.

### F5 — Chat en streaming
- **AC5.1 (I)** Étant donné un corpus indexé, quand un USER pose une question couverte, alors la réponse a le type `text/event-stream`, contient au moins 2 événements `token` et se termine par un événement `done`.
- **AC5.2 (U)** Le prompt système demande de répondre en français et uniquement à partir du contexte fourni.
- **AC5.3 (E)** Dans l'UI, la réponse s'affiche progressivement, avant la fin de la génération.

### F6 — Citations
- **AC6.1 (I)** Toute réponse non refusée émet un événement `sources` avec au moins une entrée `{ fileName, page?, excerpt, score }`, avec un `excerpt` de 300 caractères maximum.
- **AC6.2 (EV)** Pour les questions du jeu d'évaluation, le document attendu figure dans les 4 premières sources (voir AC-EV1).
- **AC6.3 (E)** Dans l'UI, les sources sont affichées sous la réponse et dépliables.

### F7 — Historique
- **AC7.1 (I)** Après une question sans `conversationId`, une conversation est créée et renvoyée. Elle apparaît dans `GET /api/conversations`.
- **AC7.2 (I)** Étant donné une conversation contenant « Combien de jours de télétravail par semaine ? », quand l'utilisateur envoie « Et pour les cadres ? » avec le même `conversationId`, alors le prompt envoyé au LLM (capturé par le mock) contient l'échange précédent.
- **AC7.3 (I)** Quand l'utilisateur A demande la conversation de l'utilisateur B, alors la réponse est `404`.
- **AC7.4 (E)** Après un rechargement de la page, l'utilisateur retrouve sa conversation et peut la poursuivre.

### F8 — Refus hors corpus
- **AC8.1 (I)** Quand un USER demande « Quelle est la capitale du Pérou ? », alors la réponse est exactement le message de refus, sans événement `sources`, et le mock du `ChatModel` n'est **jamais** appelé.
- **AC8.2 (U)** Le seuil de similarité est lu depuis la configuration. Le modifier change la décision de refus pour un score donné.
- **AC8.3 (EV)** 100 % des questions pièges du jeu d'évaluation sont refusées.

### F9 — Frontend
- **AC9.1 (E)** Un USER connecté ne voit pas le lien « Documents », et l'accès direct à `/documents` le redirige.
- **AC9.2 (E)** Un ADMIN importe un fichier par glisser-déposer et voit son statut passer à `INDEXED` sans recharger la page.
- **AC9.3 (U, Vitest/RTL)** Le composant de chat affiche un état de chargement, les tokens reçus et un message d'erreur lorsqu'un événement `error` arrive.
- **AC9.4 (E)** La déconnexion supprime le token et renvoie vers la page de connexion.

### Exigences non fonctionnelles
- **AC-NF1 (M)** Sur le poste de référence, avec le corpus de démo, le premier token arrive en moins de 10 s sur 5 questions consécutives.
- **AC-NF4 (M)** Sur une machine propre avec Docker, `cp .env.example .env && docker compose up` permet d'ouvrir l'UI et de se connecter avec les comptes seed.
- **AC-NF6 (CI)** GitHub Actions exécute le build, les tests backend (unitaires et d'intégration), les tests frontend (Vitest) et le lint (ESLint et formatage Java). Tout échec rend le pipeline rouge.
- **AC-NF3 (M)** `git grep` ne trouve aucun secret (clé JWT, mot de passe) dans le dépôt.

### Évaluation RAG
- **AC-EV1 (EV)** Sur le jeu de 15 questions (12 couvertes et 3 pièges), le **hit rate@4** (document attendu parmi les 4 premières sources) est d'au moins 80 % sur les questions couvertes.
- **AC-EV2 (EV)** Au moins 70 % des réponses aux questions couvertes contiennent tous les mots-clés attendus.
- **AC-EV3** L'évaluation se lance en une commande et produit un rapport (Markdown ou JSON) versionnable.

## 9. Jeu de démo et évaluation

**Corpus fictif « Acme SAS »** (rédigé pour le projet, sans problème de droits), dans `demo-data/` :
1. Politique de télétravail (PDF)
2. Congés et absences (DOCX)
3. Notes de frais et déplacements (PDF)
4. Onboarding IT : comptes, VPN, matériel (Markdown)
5. Charte de sécurité informatique (PDF)
6. Mutuelle et avantages sociaux (DOCX)
7. FAQ RH (TXT)

**Format du fichier d'évaluation** (`eval/questions.yaml`) :

```yaml
- id: q01
  question: "Combien de jours de télétravail sont autorisés par semaine ?"
  expectedDocument: "politique-teletravail.pdf"
  expectedKeywords: ["2 jours"]
  shouldRefuse: false
- id: q13
  question: "Quel est le cours de l'action Acme aujourd'hui ?"
  shouldRefuse: true
```

## 10. Jalons suggérés

| Jalon | Contenu | Critères couverts |
|-------|---------|-------------------|
| M1 | Socle : compose, Postgres/pgvector, Flyway, authentification JWT, CI minimale | F1, NF3, NF6 |
| M2 | Ingestion : import, validation, indexation asynchrone, gestion des documents | F2, F3, F4 |
| M3 | Chat : retrieval, refus, streaming SSE, citations | F5, F6, F8 |
| M4 | Historique des conversations | F7 |
| M5 | Frontend complet | F9 |
| M6 | Corpus de démo, évaluation, E2E, README (GIF, schéma) | EV, NF1, NF4 |

## 11. Questions ouvertes

- Modèle de chat Ollama définitif selon la RAM disponible (`llama3.1:8b`, `mistral:7b` ou `qwen2.5:7b`), à trancher au M3 sur le critère AC-NF1.
- Taille des chunks (point de départ : environ 800 tokens, chevauchement de 100) et seuil de similarité : à ajuster au M6 à partir des résultats de l'évaluation.
- Rendu du texte Markdown dans les réponses du chat : optionnel pour le MVP.
