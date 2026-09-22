# Processor stopped after a PostgreSQL outage

Scope: M4 source-built JARs. Published M2 images (`cca00ee`) do not have these
health endpoints. Recovery is manual, with at-least-once replay and idempotent
database effects; there is no exactly-once or outbox guarantee.

## Reproducible automated exercise

From the repository root on Windows with JDK 25 and Docker Desktop Linux:

```powershell
.\mvnw.cmd -B -ntp -pl payment-e2e-tests -am '-Dit.test=PaymentFlowIT#databaseOutageLeavesOffsetUncommittedUntilManualRestartAndReplayIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
```

The test uses Kafka 4.1.1, PostgreSQL 17.6 and actual application JARs. It verifies:

1. A valid payment is persisted and acknowledged; listener/readiness return 200.
2. PostgreSQL stops; HTTP intake still publishes an event to Kafka.
3. Processing fails and the listener stops: listener/readiness return 503,
   `state=STOPPED`; liveness stays 200 and the processor JVM remains alive.
4. The committed offset remains unchanged. PostgreSQL restarts; the failed payment
   has no row, the offset is still unchanged and the listener remains DOWN.
5. Manual processor restart restores listener/readiness to 200, replays the event,
   inserts one row and advances the offset. The original payment is unchanged.

Logs and the Failsafe report are under
`payment-e2e-tests/target/failsafe-reports/`; application logs are in the directory
named after the test method. Cleanup closes processes/clients and removes only
the test's containers, including after failure. This is a stopped server exercise,
not a network-partition or process-crash test.

## Manual local JAR exercise

Use a dedicated local development environment with the development connection
settings from README. Do not run a second processor group against the same topic
while inspecting offsets. Build the JARs with `.\mvnw.cmd clean verify`, then start
only infrastructure with `docker compose up -d kafka postgres kafka-init`.
If application containers are already running in this development environment,
stop them before launching the JARs. Align POSTGRES/KAFKA environment variables
with your Compose settings; the defaults below assume the documented dev setup.

Run each JAR in its own terminal from the repository root:

```powershell
java -jar transaction-api/target/transaction-api-0.0.1-SNAPSHOT.jar
java -jar transaction-processor/target/transaction-processor-0.0.1-SNAPSHOT.jar
```

In a third PowerShell terminal:

```powershell
curl.exe -i http://127.0.0.1:8081/actuator/health/listener
curl.exe -i http://127.0.0.1:8081/actuator/health/readiness
docker compose stop postgres
$transactionId = 'TX-INCIDENT-' + [guid]::NewGuid().ToString('N')
$body = @{ transactionId=$transactionId; correlationId=$transactionId; accountId='ACC-DEMO'; amount=5; currency='EUR'; type='TRANSFER' } | ConvertTo-Json
Invoke-RestMethod http://127.0.0.1:8080/api/v1/transactions -Method Post -ContentType application/json -Headers @{'Idempotency-Key'=$transactionId} -Body $body
docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 --describe --group transaction-processor-local
curl.exe -i http://127.0.0.1:8081/actuator/health/listener
curl.exe -i http://127.0.0.1:8081/actuator/health/readiness
curl.exe -i http://127.0.0.1:8081/actuator/health/liveness
```

Wait for the processing failure (the default pool connection timeout can delay
detection). Capture the affected partition's committed offset and end offset:
the failed record remains pending. A 202 confirms Kafka publication only.
Health carries no transaction data; correlate the sanitized processing log using
the chosen correlation ID. Do not paste raw events, credentials or SQL errors
into incident evidence.

Restore the database and confirm that this alone does not recover the listener:

```powershell
docker compose start postgres
docker compose ps postgres
curl.exe -i http://127.0.0.1:8081/actuator/health/listener
docker compose exec -T postgres psql -U payments -d payments -c "SELECT count(*) FROM ledger_transactions WHERE transaction_id='$transactionId';"
```

Once PostgreSQL is healthy, the count must still be zero. In the processor terminal,
press Ctrl+C and rerun its `java -jar` command. Check listener/readiness 200, repeat
the count query (one row) and the consumer-group query (offset advanced). Do not
reset offsets or delete records to make the health check green. Stop both JARs
with Ctrl+C when finished; `docker compose down` retains development data volumes.

## Kubernetes probe configuration

The chart's default processor digest remains M2, so its processor probes remain
disabled. With a locally built M4 image loaded into the intended kind cluster,
set `processor.image=<local-image>` and `processor.healthProbes.enabled=true`
on the Helm upgrade, always supplying that cluster's kubeconfig/context/namespace.
No image has been published by this milestone. Check the rendered chart first.

Startup/liveness target `/actuator/health/liveness`; readiness targets
`/actuator/health/readiness`, on pod port 8081. Readiness failure does not restart
or pause Kafka. Repair PostgreSQL and explicitly restart the processor deployment.
Do not point liveness at `/actuator/health` or `/actuator/health/listener`: that
would introduce automatic failure replay. The optional Helm configuration needs
runtime validation with the new image before claiming Kubernetes M4 acceptance.
