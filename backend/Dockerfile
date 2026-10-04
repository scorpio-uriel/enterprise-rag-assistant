# Image multi-stage : Maven compile le jar, l'image finale ne contient que le JRE et le jar.
#   docker build -t rag-backend backend/

# --- Étape 1 : build ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Le pom seul d'abord : cette couche (dépendances) reste en cache tant que le pom ne change pas.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests et Spotless tournent déjà en CI (./mvnw verify) : inutile de les rejouer ici.
RUN mvn -B -q package -DskipTests -Dspotless.check.skip=true \
    && cp target/*.jar app.jar

# --- Étape 2 : exécution ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# Utilisateur non-root : une faille dans l'application ne donne pas root dans le conteneur.
RUN groupadd --system app && useradd --system --gid app app \
    && mkdir -p /app/data/uploads && chown -R app:app /app/data
USER app

COPY --from=build /build/app.jar app.jar

ENV STORAGE_DIR=/app/data/uploads
EXPOSE 8080

# MaxRAMPercentage : la JVM dimensionne son tas d'après la limite mémoire du conteneur.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
