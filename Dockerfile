FROM eclipse-temurin:17-jre:17-jammy
WORKDIR /app

# Copy the JAR file (built locally or via CI)
COPY build/libs/*.jar app.jar

EXPOSE 8080

# Health check endpoint (make sure you have this in your Spring Boot app)
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]