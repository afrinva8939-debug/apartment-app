FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy backend jar + dependencies
COPY ApartmentApp.jar /app/ApartmentApp.jar
COPY libs /app/libs

# Copy frontend static files so StaticHandler can serve them
COPY app /app/app

# Run with Clever Cloud's dynamic PORT
CMD ["sh", "-c", "java -cp /app/ApartmentApp.jar:/app/libs/* staticwebserver.SimpleHttpServer --server.port=$PORT"]
