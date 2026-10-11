FROM eclipse-temurin:25-jdk-noble@sha256:2feab631bffce6236d8bb5261a4abe19a8d6f85bad1c01166f74686c983d011f AS build
WORKDIR /workspace
COPY . .
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2,sharing=locked ./mvnw -B -ntp -pl transaction-processor -am -Dmaven.test.skip=true package

FROM eclipse-temurin:25-jre-noble@sha256:b573af9e331196fbc42e246da4df24df9b6c556c73e7efddfde0511f1c9508c5

# Refresh affected Ubuntu packages in the pinned base; fail if the security fix is unavailable.
RUN apt-get update && apt-get install -y --no-install-recommends --only-upgrade openssl libssl3t64 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' openssl)" ge 3.0.13-0ubuntu3.16 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libssl3t64)" ge 3.0.13-0ubuntu3.16 \
    && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 payments && useradd --uid 10001 --gid payments --no-create-home --shell /usr/sbin/nologin payments
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/transaction-processor/target/transaction-processor-*.jar /app/application.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
