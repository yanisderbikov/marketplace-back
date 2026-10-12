FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

RUN mvn clean package -DskipTests



FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-Xms256m", "-Xmx384m", "-XX:MetaspaceSize=128m", "-XX:MaxMetaspaceSize=256m", "-XX:+ExitOnOutOfMemoryError", "-XX:+UnlockExperimentalVMOptions", "-XX:G1MaxNewSizePercent=25", "-XX:G1PeriodicGCInterval=900000", "-Xlog:gc=info,gc+metaspace=info", "-jar", "app.jar"]
