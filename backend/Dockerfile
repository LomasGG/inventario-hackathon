FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -q -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /app/target/*.jar /app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app.jar"]