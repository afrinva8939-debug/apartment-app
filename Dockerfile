# Use a small, stable Java runtime
FROM eclipse-temurin:21-jre-jammy

# Create app directory
WORKDIR /app

# Copy the runnable jar and any libs folder
# (ApartmentApp.jar and libs/ should be in the repo root)
COPY ApartmentApp.jar /app/ApartmentApp.jar
COPY libs /app/libs
COPY META-INF /app/META-INF
COPY staticwebserver /app/staticwebserver
COPY app /app/app

# Expose the port Clever Cloud will give (we expect the app to use $PORT)
EXPOSE 8080

# CMD: run the jar; the app will read PORT env var at runtime.
# Use -cp to ensure libs on /app/libs/* are on the classpath if your code needs them.
CMD ["sh", "-c", "java -cp /app/ApartmentApp.jar:/app/libs/* staticwebserver.SimpleHttpServer"]
