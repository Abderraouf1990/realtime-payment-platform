FROM eclipse-temurin:25-jdk-noble@sha256:2feab631bffce6236d8bb5261a4abe19a8d6f85bad1c01166f74686c983d011f AS build
WORKDIR /workspace
COPY . .
# Normalize the wrapper when the build context comes from a Windows checkout.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
# Tests run separately via clean verify; no Docker socket is required inside this build.
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -pl transaction-api -am -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble@sha256:b573af9e331196fbc42e246da4df24df9b6c556c73e7efddfde0511f1c9508c5
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 payments \
    && useradd --uid 10001 --gid payments --no-create-home --shell /usr/sbin/nologin payments
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/transaction-api/target/transaction-api-*.jar /app/application.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
