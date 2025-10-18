FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy backend jar + dependencies
COPY ApartmentApp.jar /app/ApartmentApp.jar
COPY libs /app/libs

# ✅ Copy frontend (HTML, CSS, JS)
COPY app /app/app

# Run with Clever Cloud’s provided port
CMD ["sh", "-c", "java -cp /app/ApartmentApp.jar:/app/libs/* staticwebserver.SimpleHttpServer --server.port=$PORT"]
