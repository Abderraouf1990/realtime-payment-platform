"""Fail closed on scanner errors and findings; never print secret values."""
import argparse
import json
from pathlib import Path


def trivy_findings(report):
    if report.get("SchemaVersion") != 2 or "ArtifactName" not in report:
        raise ValueError("Missing or unsupported Trivy report")
    findings = []
    for result in report.get("Results", []):
        for vulnerability in result.get("Vulnerabilities", []):
            if vulnerability["Severity"] in ("HIGH", "CRITICAL"):
                findings.append(f"{vulnerability['VulnerabilityID']} ({vulnerability['Severity']}) {vulnerability['PkgName']}")
        for secret in result.get("Secrets", []):
            findings.append(f"Secret rule {secret['RuleID']} in {result['Target']}:{secret.get('StartLine', '?')}")
    return findings


def redact_secrets(report):
    for result in report.get("Results", []):
        if "Secrets" in result:
            result["Secrets"] = [{key: value for key, value in secret.items()
                                  if key in {"RuleID", "Category", "Severity", "StartLine", "EndLine", "Title"}}
                                 for secret in result["Secrets"]]
    return report


def sarif_findings(report):
    if report.get("version") != "2.1.0" or not report.get("runs"):
        raise ValueError("Missing or unsupported SARIF report")
    findings = []
    for run in report["runs"]:
        if not isinstance(run.get("results"), list):
            raise ValueError("Missing SARIF results")
        for invocation in run.get("invocations", []):
            if invocation.get("executionSuccessful") is False:
                raise ValueError("CodeQL execution failed")
        # All security-extended findings block; no severity or suppression bypass.
        findings.extend(result.get("ruleId", "unknown-rule") for result in run.get("results", []))
    return findings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("trivy", "sarif"))
    parser.add_argument("reports", nargs="+")
    parser.add_argument("--sanitized-output")
    args = parser.parse_args()
    findings = []
    for filename in args.reports:
        path = Path(filename)
        report = json.loads(path.read_text(encoding="utf-8-sig"))
        if args.kind == "trivy":
            # Sanitize artifacts before returning any non-zero policy result.
            report = redact_secrets(report)
            if not args.sanitized_output or len(args.reports) != 1:
                raise ValueError("Trivy requires one input and --sanitized-output")
            Path(args.sanitized_output).write_text(json.dumps(report, indent=2), encoding="utf-8")
            findings.extend(trivy_findings(report))
        else:
            findings.extend(sarif_findings(report))
    print("\n".join(findings) if findings else "Security gate passed")
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
