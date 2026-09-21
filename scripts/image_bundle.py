"""Bind tested image archives/SBOMs to a source SHA before manual promotion."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

MODULES = ("transaction-api", "transaction-processor")


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inspect(image):
    return json.loads(subprocess.check_output(["docker", "image", "inspect", image], text=True))[0]


def validate_revision(revision):
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("Expected a full lowercase Git SHA")


def verify_files(root, manifest, revision):
    validate_revision(revision)
    if manifest.get("revision") != revision or set(manifest.get("images", {})) != set(MODULES):
        raise ValueError("Bundle does not match the approved commit/modules")
    for module in MODULES:
        for filename in ("image.tar", "sbom.cdx.json"):
            if digest(root / module / filename) != manifest["images"][module]["sha256"][filename]:
                raise ValueError(f"Modified bundle file: {module}/{filename}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("create", "publish"))
    parser.add_argument("--revision", required=True)
    parser.add_argument("--directory", default="artifacts/images")
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    validate_revision(args.revision)
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", args.repository):
        raise ValueError("Invalid repository")
    root = Path(args.directory)
    manifest_path = root / "manifest.json"
    if args.mode == "create":
        manifest = {"revision": args.revision, "repository": args.repository, "images": {}}
        for module in MODULES:
            image = f"payments/{module}:{args.revision}"
            metadata = inspect(image)
            if metadata["Config"].get("Labels", {}).get("org.opencontainers.image.revision") != args.revision:
                raise ValueError("Image revision label does not match")
            if metadata["Config"]["User"] != "10001:10001":
                raise ValueError("Expected non-root runtime image")
            sbom = json.loads((root / module / "sbom.cdx.json").read_text())
            if sbom.get("bomFormat") != "CycloneDX" or not sbom.get("components"):
                raise ValueError("Missing image inventory")
            manifest["images"][module] = {"tag": image, "imageId": metadata["Id"], "sha256": {
                name: digest(root / module / name) for name in ("image.tar", "sbom.cdx.json")}}
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    else:
        manifest = json.loads(manifest_path.read_text())
        verify_files(root, manifest, args.revision)
        if manifest.get("repository") != args.repository:
            raise ValueError("Bundle repository does not match")
        # Verify both archives before the first registry write.
        for module in MODULES:
            subprocess.run(["docker", "load", "--input", str(root / module / "image.tar")], check=True)
            metadata = inspect(manifest["images"][module]["imageId"])
            if metadata["Config"].get("Labels", {}).get("org.opencontainers.image.revision") != args.revision:
                raise ValueError("Loaded image revision mismatch")
        published = {}
        for module in MODULES:
            target = f"ghcr.io/{args.repository.lower()}-{module}:sha-{args.revision}"
            subprocess.run(["docker", "tag", manifest["images"][module]["imageId"], target], check=True)
            subprocess.run(["docker", "push", target], check=True)
            published[module] = {"tag": target, "digests": inspect(target)["RepoDigests"],
                                 "imageId": manifest["images"][module]["imageId"]}
            # Preserve evidence if a later image push fails; registry writes are not atomic.
            (root / "published-images.json").write_text(json.dumps(published, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
