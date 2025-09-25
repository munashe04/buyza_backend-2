FROM eclipse-temurin:17-jre-alpine

# Create app directory
WORKDIR /app

# Copy the source code and build the application
COPY . .
RUN ./mvnw clean package -DskipTests

# Copy the built JAR file (adjust the path if needed)
COPY target/*.jar app.jar

# Create logs directory
RUN mkdir -p logs

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]