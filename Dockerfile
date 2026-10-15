# Build stage: compila el jar dentro de la imagen para que `docker build .` funcione
# sin necesidad de un `mvn package` previo (igual que payments).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Capa de dependencias: se cachea mientras el pom no cambie.
COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q clean package -DskipTests
# Normaliza el nombre del jar (excluye el .jar.original).
RUN cp "$(ls target/*.jar | grep -v original)" /app/app.jar

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=build /app/app.jar app.jar

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "app.jar"]
