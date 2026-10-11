package com.aayadi.payment.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CorpusEvaluationTests {
    @TempDir Path temp;
    @Test void threeRepetitionsKeepGoldAndBlockedCasesOutsideInference() throws Exception {
        var calls = new AtomicInteger(); var checkpoints = new AtomicInteger();
        var json = JsonMapper.builder().build();
        var report = CorpusEvaluation.evaluate(Path.of(System.getProperty("repository.root")), (input, timeout) -> {
            calls.incrementAndGet();
            assertThat(json.writeValueAsString(input)).doesNotContain("expected", "mustMention", "mustNotClaim", "fixture-user",
                    "incident-for-", "FAKE-SECRET-CANARY", "FAKE-ACCOUNT-CANARY", "OVERRIDE-CANARY");
            return new DiagnosisRunner.Reply(ResponsePolicy.proposal("unknown", List.of(), List.of()).toString(), null);
        }, null, () -> false, r -> checkpoints.incrementAndGet());
        report.put("mode", "offline-fake-preparation");
        assertThat(calls).hasValue(39); assertThat(checkpoints).hasValue(54);
        assertThat(report.get("cases").size()).isEqualTo(54);
        assertThat(report.path("latency").path("samples").asInt()).isEqualTo(39);
        assertThat(report.get("humanCorrectness").isNull()).isTrue();
        assertThat(report.get("totalEstimatedCost").isNull()).isTrue();
        assertThat(report.get("unknownCostInvocations").asInt()).isEqualTo(39);
        int blocked = 0;
        for (var row : report.get("cases")) {
            assertThat(row.get("accessBoundaryPassed").asBoolean()).isTrue();
            if (!row.get("contextStatus").asString().equals("ready")) {
                blocked++; assertThat(row.get("adapterInvocations").asInt()).isZero();
            }
        }
        assertThat(blocked).isEqualTo(15);
        Files.writeString(Path.of("target/evaluation-preparation.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    @Test void haltedCampaignStillReportsEveryCaseWithoutFurtherInference() throws Exception {
        var report = CorpusEvaluation.evaluate(Path.of(System.getProperty("repository.root")), (input, timeout) -> {
            throw new AssertionError("Must not invoke halted provider");
        }, null, () -> true, r -> {});
        assertThat(report.get("cases").size()).isEqualTo(54);
        assertThat(report.path("latency").path("samples").asInt()).isZero();
        assertThat(report.path("latency").path("p95Millis").isNull()).isTrue();
    }
    @Test void campaignMarkerSurvivesAndRefusesASecondProcessClaim() throws Exception {
        Path marker = temp.resolve("campaign.started"); CorpusEvaluation.claimCampaign(marker);
        assertThatThrownBy(() -> CorpusEvaluation.claimCampaign(marker)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(marker)).contains("Do not delete or rerun");
    }
}
