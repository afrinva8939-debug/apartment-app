# Use small Java runtime
FROM eclipse-temurin:21-jre-jammy

# Create app directory
WORKDIR /app

# Copy the runnable jar and the libs folder
COPY ApartmentApp.jar /app/ApartmentApp.jar
COPY libs /app/libs

# Expose port (Clever Cloud will map it)
EXPOSE 8000

# Default PORT env (Clever Cloud will override this)
ENV PORT=8000

# Run the jar
ENTRYPOINT ["sh", "-c", "java -jar /app/ApartmentApp.jar"]

# Dockerfile (place at the root of apartment-app)
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copy runnable jar + libs folder (you commit these to your repo)
COPY ApartmentApp.jar /app/ApartmentApp.jar
COPY libs /app/libs

EXPOSE 8000
ENV PORT=8000

ENTRYPOINT ["sh", "-c", "java -jar /app/ApartmentApp.jar"]
