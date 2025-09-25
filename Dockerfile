FROM eclipse-temurin:17-jre-alpine

# Install required packages
RUN apk add --no-cache tzdata
ENV TZ=Africa/Harare

# Create app user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

# Create app directory
WORKDIR /app

# Copy the JAR file
COPY target/buyza-bot-*.jar app.jar

# Create logs directory
RUN mkdir -p /app/logs

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/buyza/health || exit 1

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "/app/app.jar"]