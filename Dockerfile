FROM openjdk:17-jdk-alpine

WORKDIR /app

# Copy Gradle wrapper files
COPY gradle/ gradle/
COPY gradlew .
COPY gradlew.bat .
COPY build.gradle .
COPY settings.gradle .

# Make gradlew executable
RUN chmod +x ./gradlew

# Download dependencies (cached layer)
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src/ src/

# Build the application
RUN ./gradlew clean build -x test --no-daemon

# Run the application
EXPOSE 8080
CMD ["java", "-jar", "build/libs/Buyza-0.0.1-SNAPSHOT.jar"]