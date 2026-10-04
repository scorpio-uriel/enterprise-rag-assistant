# Image multi-stage : Node construit dist/, nginx le sert et relaie /api vers le backend.
#   docker build -t rag-frontend frontend/

# --- Étape 1 : build ---
FROM node:24-alpine AS build
WORKDIR /build

# package*.json seuls d'abord : la couche node_modules reste en cache tant qu'ils ne changent pas.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# --- Étape 2 : exécution ---
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
