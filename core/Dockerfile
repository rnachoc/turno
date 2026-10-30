# Author: Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
FROM eclipse-temurin:26.0.2.1_1-jdk-noble AS build
# Without unzip, mvnw fetches the .tar.gz, which does not match the checksum pinned for the .zip.
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends unzip
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -ntp -q package -DskipTests
RUN java -Djarmode=tools -jar target/turno-core.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:26.0.2.1_1-jre-noble
RUN groupadd --system --gid 10001 turno && useradd --system --uid 10001 --gid turno --no-create-home turno
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
