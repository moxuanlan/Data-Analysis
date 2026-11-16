FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY DataLens-backend/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline
COPY DataLens-backend/src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN useradd --system --uid 10001 --create-home datalens
COPY --from=build /build/target/datalens-0.1.0.jar /app/datalens.jar
USER datalens
ENV SPRING_PROFILES_ACTIVE=cloud
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/datalens.jar"]
