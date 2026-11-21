# ---- build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system swapp && useradd --system --gid swapp --home /app swapp \
    && mkdir -p /app/data && chown -R swapp:swapp /app
COPY --from=build /workspace/target/swapp.jar /app/swapp.jar
USER swapp

# Sized for small containers (Render free = 512 MB, 0.1 CPU): heap at 60% leaves room for metaspace, code cache and
# buffers so the kernel never OOM-kills the process; SerialGC has the smallest footprint; tiered level 1 (C1 only)
# makes startup several times faster on a fraction of a CPU, at little cost for an I/O-bound web app.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError" \
    PORT=8080
EXPOSE 8080

# Health endpoint for the platform: GET /actuator/health
ENTRYPOINT ["java", "-jar", "/app/swapp.jar"]
