# shadow-cljs needs Node and a JVM.
FROM node:24-bookworm-slim AS build
COPY --from=eclipse-temurin:21-jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY shadow-cljs.edn ./
COPY src src
COPY public public
RUN npx shadow-cljs release app

# Local stand-in for Vercel: static files + /api proxied to the backend.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/public /usr/share/nginx/html
