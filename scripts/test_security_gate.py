import copy
import unittest
from security_gate import redact_secrets, sarif_findings, trivy_findings


class SecurityGateTests(unittest.TestCase):
    def test_high_and_critical_block_even_without_a_fix(self):
        report = {"SchemaVersion": 2, "ArtifactName": "image", "Results": [{"Vulnerabilities": [
            {"Severity": level, "VulnerabilityID": level, "PkgName": "library"}
            for level in ("LOW", "MEDIUM", "HIGH", "CRITICAL")]}]}
        self.assertEqual(len(trivy_findings(report)), 2)

    def test_secret_redaction_keeps_gate_and_location_without_content(self):
        report = {"SchemaVersion": 2, "ArtifactName": "source", "Results": [{"Target": "file", "Secrets": [
            {"RuleID": "fixture", "Severity": "LOW", "StartLine": 3,
             "Match": "private-value", "Code": {"Lines": ["private-value"]}}]}]}
        redacted = redact_secrets(copy.deepcopy(report))
        self.assertNotIn("private-value", str(redacted))
        self.assertEqual(trivy_findings(redacted), ["Secret rule fixture in file:3"])

    def test_missing_reports_fail_closed(self):
        for report in ({}, {"SchemaVersion": 1, "ArtifactName": "image"}):
            with self.assertRaises(ValueError):
                trivy_findings(report)
        with self.assertRaises(ValueError):
            sarif_findings({"version": "2.1.0", "runs": []})
        with self.assertRaises(ValueError):
            sarif_findings({"version": "2.1.0", "runs": [{}]})

    def test_codeql_findings_cannot_be_suppressed_by_report_metadata(self):
        report = {"version": "2.1.0", "runs": [{"results": [
            {"ruleId": "java/sql-injection", "level": "warning", "suppressions": [{"status": "accepted"}]}]}]}
        self.assertEqual(sarif_findings(report), ["java/sql-injection"])

    def test_codeql_execution_failure_is_not_a_clean_scan(self):
        with self.assertRaises(ValueError):
            sarif_findings({"version": "2.1.0", "runs": [{"results": [], "invocations": [{"executionSuccessful": False}]}]})

    def test_clean_reports_pass(self):
        self.assertEqual(trivy_findings({"SchemaVersion": 2, "ArtifactName": "image", "Results": []}), [])
        self.assertEqual(sarif_findings({"version": "2.1.0", "runs": [{"results": []}]}), [])


if __name__ == "__main__":
    unittest.main()
