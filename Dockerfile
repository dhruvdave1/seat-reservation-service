# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && cp target/seat-reservation-service-*.jar application.jar \
 && java -Djarmode=tools -jar application.jar extract --layers --destination /extracted

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
# Layers ordered from least to most frequently changing for cache reuse.
# Result: /app/application.jar plus /app/lib/*.jar. The AOT cache needs plain jars
# on the classpath, which is why this is not the exploded JarLauncher layout.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
# Free tiers give ~512 MB and a fraction of a CPU: size the heap from the container
# limit, and pin the GC the JVM would pick there anyway so training and runtime match.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC"
# AOT cache training run (JDK 25, JEP 514/515): start the context, exit before Tomcat
# starts. Flyway is off because no database exists at build time; nothing else connects.
# MaxRAM makes the build host size the heap as if it had the runtime's 512 MB: on a
# large builder 75% of RAM exceeds 32 GB, compressed oops turn off, and the JVM then
# rejects the cache at runtime ("saved state of UseCompressedOops ... is different").
RUN java -XX:MaxRAM=512m -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh \
        -Dspring.flyway.enabled=false -jar application.jar \
 && chown app:app app.aot
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "application.jar"]
