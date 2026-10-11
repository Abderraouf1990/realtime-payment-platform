# ADR 0008: Scan and promote the tested application images

Date: 2026-09-21

Status: Implemented and validated, including GHCR delivery for cca00ee.

## Context

M2 follows the containerized payment baseline. We need reproducible security
checks and commit-traceable delivery without granting pull requests registry
credentials or silently publishing images. Payment behavior is unchanged.

## Decision

CI retains Java 25 `./mvnw clean verify` and adds four independent acceptance
areas: tested application code, checkout secrets, CodeQL Java security analysis,
and application image construction/Compose validation/security inventory.
Actions are pinned to commit SHAs; scanner, linter and Java base images to digests.

The images job builds both images with OCI source/revision labels, exercises
those exact tags using the existing Compose acceptance script, then exports each
image to a Docker archive. Trivy scans those archives for OS/JAR vulnerabilities
and secrets and generates CycloneDX SBOMs. Scanning does not mount the Docker
socket. A manifest binds the source SHA, image IDs, archives and SBOM hashes.
Only successful image checks make the archives available for promotion.

Blocking policy (no exceptions currently configured):

- Any failing Maven test, Compose acceptance check or security-gate unit test.
- Every CodeQL `security-extended` result, including suppressed SARIF results.
- Every HIGH or CRITICAL Trivy vulnerability, including findings without a fix.
- Every Trivy secret finding, regardless of severity. Raw matched values/code
  are removed before report upload; raw hidden reports are never uploaded.
- Scanner/database/network errors, missing or invalid reports and modified
  promotion archives/SBOMs. An unavailable check is not a passing check.

LOW/MEDIUM vulnerabilities remain visible in reports but do not block. Any future
exception needs a reviewed reason, scope, owner and expiry; there is no generic
ignore file or `continue-on-error` bypass. Vulnerability databases remain current,
so an identical image can fail a later scan as advisories change.

Normal pushes and PRs only validate. Publication requires an explicit manual CI
dispatch on `main` or `codex/build-mvp`, `publish=true`, and `approved_sha` equal
to the full SHA of the selected ref. A mismatch skips publication. All checks run
again in that same workflow execution. The publication job alone has
`packages: write`; other jobs have `contents: read`. CodeQL writes a SARIF
artifact, not a code-scanning upload, so no `security-events: write` is needed.
No PAT or new secret is configured: GHCR uses the job-scoped `GITHUB_TOKEN`.

The publication job downloads only its own run's verified bundle, validates both
archives/SBOMs and revision labels, and loads rather than rebuilds the images.
Tags are `ghcr.io/<owner>/<repository>-<module>:sha-<full-commit-sha>`.
Registry digests are recorded in `published-images.json` after each successful
push, including partial success. The two registry writes are not atomic. Consumers
should use the recorded digest: a SHA-named tag alone is not registry-enforced
immutability, a signature or a provenance attestation.

## Scope and tradeoffs

- CodeQL covers the compiled application modules and shared contracts. Runtime
  dependency scanning covers packages/JARs present in the final images; it does
  not inventory every test dependency, Maven plugin or discarded builder layer.
- Checkout secret analysis covers the current tree, not Git history. Generated
  targets, local artifacts and Git metadata are excluded. Its offline mode avoids
  Maven dependency resolution; image vulnerability scanning still updates its DB.
- Images/SBOMs are run artifacts, retained for seven days for promotion. SBOMs
  are not published as OCI attachments. No signing or deployment is claimed.
- Repository branch protection must require the validation jobs if merge blocking
  is desired; workflow failure alone does not configure repository protection.
- Manual dispatch availability requires the workflow on the default branch.
  GHCR permissions/package policy require validation in the target repository;
  the first verified delivery is recorded below.
- Strict gates can block delivery of the existing baseline. Findings must be
  fixed or explicitly reviewed; passing functional tests does not waive them.

## Verification

`scripts/test_security_gate.py` exercises severity thresholds, secret redaction,
malformed/missing reports and SARIF failure/suppression handling.
`scripts/test_image_bundle.py` exercises full-SHA validation and tamper detection.
`scripts/verify-compose.ps1` accepts both prebuilt image tags and keeps its isolated
project, restart/data-retention checks and cleanup. See PROJECT_STATE.md for
measured local results and outstanding remote criteria.

On 2026-09-22, verified [CI run 35653382625](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35653382625)
for cca00ee: build, secrets, CodeQL and image jobs succeeded, including Compose,
both image gates and bundle creation. Publication was skipped on push, as designed.
GHCR delivery remains subject to explicit approval and validation. A manual dispatch
repeats all checks and promotes its own bundle; it does not promote the earlier run's
archives. Registry digests must be verified after publication before closing M2.

After explicit user approval, [manual run 35661616528](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/35661616528)
repeated all gates and published both images for cca00ee. Independent verification
checked the Actions artifact digests, both archive/SBOM hashes, the remote GHCR
manifest/config digests, source/revision labels and UID/GID 10001:10001 against the
tested bundle. [Publication evidence](../evidence/m2-publication.json) records the
complete image references and digests. M2 is complete for this revision; the
non-atomic publication, unsigned-artifact and scan-coverage limitations above remain.

Delivery exercise: inspect a published image by its recorded `@sha256:...` reference,
compare its config digest with the evidence `imageId`, and explain why that digest
differs from both the registry manifest digest and the downloadable archive hash.

Reproducible exercise: run the source scan and a scan of a locally built image,
inspect its `trivy.json` alongside `sbom.cdx.json`, and run the Python tests to
observe that adding a HIGH finding or modifying a bundle file blocks promotion.

## Dependency remediation (2026-09-21)

The first scans blocked both application images on Java dependencies. Upgrade the
parent from Boot 4.0.1 to the released
[Boot 4.0.8](https://spring.io/blog/2026/08/20/spring-boot-4-0-8-available-now/),
remaining on the 4.0 line rather than introducing a minor-version migration.
Its BOM aligns Spring, Micrometer, Kafka, PostgreSQL JDBC and both Jackson families.
Kafka clients 4.1.2 transitively replaces the archived `org.lz4:lz4-java:1.8.0`
with `at.yawk.lz4:lz4-java:1.10.1`; the maintainer identifies 1.10.1 as fixing the
[decompressor information leak](https://github.com/yawkat/lz4-java/security/advisories/GHSA-cmp6-m4wj-q63q).
No manual exclusion or duplicate compression library is needed.

The BOM still manages Tomcat 11.0.24. Override `tomcat.version` to the released
11.0.26 to include the subsequent
[Tomcat security fixes](https://tomcat.apache.org/security-11).
Remove this override when a future Boot BOM manages 11.0.26 or later; do not
indefinitely hold Tomcat below a newer managed version. This is a dependency
correction, not a policy exception. HIGH/CRITICAL and secret gates are unchanged.

Only application runtime dependencies are in this remediation scope. Keep the
pinned Kafka/PostgreSQL infrastructure images and the payment behavior unchanged.
Exercise: compare the before/after image SBOMs, inspect Kafka's LZ4 dependency,
and rerun `scripts/security-scan.ps1` against the rebuilt image. The final image
scan, not the selected version number alone, determines policy compliance.

## Targeted remediation after the 2026-10-11 scan

The [CI run for 81ce039](https://github.com/Abderraouf1990/realtime-payment-platform/actions/runs/38099691298)
passed functional checks but blocked current images on eight API and thirteen
processor HIGH advisory occurrences. Keep Boot 4.0.8 and the existing digest-pinned
Temurin bases; apply security patches within the existing dependency lines:

- Override Boot's Jackson BOM properties to 3.1.7 and 2.21.7, rather than updating
  only core/databind and leaving other Jackson modules on incompatible versions.
  The [maintainer advisory](https://github.com/FasterXML/jackson-core/security/advisories/GHSA-p6pp-m3f8-5c89)
  and the downloaded CI reports identify these patch lines as corrected for the
  reported Jackson findings. Full image scans still decide acceptance.
- Manage `at.yawk.lz4:lz4-java` 1.11.4 directly in the reactor's dependency
  management because Boot does not manage Kafka's transitive LZ4 dependency.
  The [maintainer's fix](https://github.com/yawkat/lz4-java/security/advisories/GHSA-mcr4-qmvw-px4g)
  changes native library extraction; no workaround or severity exception is used.
- In each runtime Dockerfile, update only `openssl` and `libssl3t64` through
  Ubuntu's repositories, then require both installed versions to be at least
  `3.0.13-0ubuntu3.16`. This is the Noble fix listed by
  [Ubuntu](https://ubuntu.com/security/CVE-2026-84782). A missing fix fails the
  build instead of producing a knowingly vulnerable image. Delete apt indexes
  before the layer completes and retain UID/GID 10001 and the pinned base digest.

Tradeoff: targeted overrides and OS updates avoid an unrelated Boot/JDK migration
but add temporary maintenance and a dependency on the current apt repositories.
The minimum fixed OS version allows later security updates; it does not pin the
apt repository snapshot. Docker may reuse a cached update layer, so a future
package remediation must rebuild it without cache (or change the pinned base).
Fresh Trivy databases and scans of the exact archives remain mandatory.
Image scans and SBOM generation use a bounded fifteen-minute Trivy timeout;
the five-minute default expired locally while downloading the Java database.
This follows the [scanner troubleshooting guidance](https://trivy.dev/docs/v0.74/guide/references/troubleshooting/#timeout).
Timeouts and other scanner errors still fail closed; the severity policy and
CI job deadline are unchanged.
Remove Java overrides when a reviewed Boot BOM manages equal or newer releases;
review the OS step when moving to a base already containing the fix.

The published M2 digests and their historical evidence do not change. This local
remediation authorizes neither another AI campaign nor image publication/push.
Exercise: compare the CI `trivy.json` installed/fixed versions with each rebuilt
image's SBOM and OS versions; explain why a successful Maven build alone did not
detect the vulnerable OS layer.
