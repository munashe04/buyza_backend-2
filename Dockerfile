# Stage 1 - build
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY COPY build.gradle.kts settings.gradle.kts ./
# Copy whole source
COPY src src

# Make gradlew executable
RUN chmod +x gradlew

# Build
RUN ./gradlew bootJar -x test --no-daemon

# Stage 2 - runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# Use non-root user for better security
RUN addgroup -S buyza && adduser -S buyza -G buyza
USER buyza

EXPOSE 8080

# Healthcheck (Render will use port 8080)
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/app/app.jar"]
