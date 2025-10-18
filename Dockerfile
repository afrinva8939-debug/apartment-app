FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY ApartmentApp.jar /app/ApartmentApp.jar
COPY libs /app/libs
CMD ["sh", "-c", "java -cp /app/ApartmentApp.jar:/app/libs/* staticwebserver.SimpleHttpServer --server.port=$PORT"]
