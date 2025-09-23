#!/bin/bash

# Build the application
echo "Building Buyza Bot..."
./mvnw clean package -DskipTests

# Build Docker image
echo "Building Docker image..."
docker build -t buyza-bot:latest .

# Deploy
echo "Deploying to production..."
docker-compose -f docker-compose.prod.yml down
docker-compose -f docker-compose.prod.yml up -d

echo "Deployment complete! Check logs with: docker-compose -f docker-compose.prod.yml logs -f"