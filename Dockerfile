# =============================================================
# Stage 1 — Frontend build (Node 18)
# =============================================================
FROM node:18 AS frontend

WORKDIR /app

# Copy package files first so npm install layer is cached when
# source changes but dependencies do not.
COPY Frontend/package*.json ./Frontend/
RUN npm install --prefix Frontend

# Copy the rest of the frontend source and build.
COPY Frontend/ ./Frontend/
RUN npm run build --prefix Frontend
# Output: /app/Frontend/dist/

# =============================================================
# Stage 2 — Backend build (Maven + JDK 17)
# =============================================================
FROM maven:3.9-eclipse-temurin-17 AS backend

WORKDIR /app

# Copy pom.xml first and pre-fetch dependencies (layer cache).
COPY Backend/pom.xml ./Backend/pom.xml
RUN mvn -f Backend/pom.xml dependency:go-offline -q

# Copy backend source.
COPY Backend/ ./Backend/

# Copy the compiled frontend into Spring Boot's static resources
# so it gets bundled into the fat JAR.
COPY --from=frontend /app/Frontend/dist/ ./Backend/src/main/resources/static/

# Build the fat JAR.
RUN mvn -f Backend/pom.xml package -DskipTests -q
# Output: /app/Backend/target/LeatherMatch-AI-0.0.1-SNAPSHOT.jar

# =============================================================
# Stage 3 — Runtime (JRE 17 only)
# =============================================================
FROM eclipse-temurin:17-jre AS runtime

WORKDIR /app

# Bake the ONNX model into the image (read-only model artifact).
COPY model/ ./model/

# Copy the fat JAR from the build stage.
COPY --from=backend /app/Backend/target/LeatherMatch-AI-0.0.1-SNAPSHOT.jar app.jar

# Activate the docker Spring profile so container-internal paths are used.
ENV SPRING_PROFILES_ACTIVE=docker

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
