# Use lightweight JDK image
FROM eclipse-temurin:17-jdk-alpine as build

# Set work directory
WORKDIR /app

# Copy Gradle wrapper & config
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# Download dependencies
RUN ./gradlew build -x test --parallel --continue || return 0

# Copy source
COPY src src

# Build the app
RUN ./gradlew clean bootJar

# --- Runtime image ---
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Copy JAR from build stage
COPY --from=build /app/build/libs/*.jar app.jar

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java","-jar","app.jar"]
