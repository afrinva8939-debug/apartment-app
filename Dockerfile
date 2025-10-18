FROM eclipse-temurin:21-jre-jammy
CMD ["sh", "-c", "java -cp /app/ApartmentApp.jar:/app/libs/* staticwebserver.SimpleHttpServer --server.port=$PORT"]
