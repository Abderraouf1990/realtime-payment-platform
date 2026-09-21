from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch
from image_bundle import MODULES, digest, main, validate_revision, verify_files


class ImageBundleTests(unittest.TestCase):
    def test_only_full_commit_ids_are_accepted(self):
        for invalid in ("main", "abc123", "A" * 40, "a" * 40 + ";command"):
            with self.assertRaises(ValueError):
                validate_revision(invalid)

    def test_archives_and_sboms_are_verified_before_promotion(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"revision": "a" * 40, "images": {}}
            for module in MODULES:
                (root / module).mkdir()
                for filename in ("image.tar", "sbom.cdx.json"):
                    (root / module / filename).write_text("test content")
                manifest["images"][module] = {"sha256": {name: digest(root / module / name)
                                                       for name in ("image.tar", "sbom.cdx.json")}}
            verify_files(root, manifest, "a" * 40)
            with self.assertRaises(ValueError):
                verify_files(root, manifest, "b" * 40)
            (root / MODULES[1] / "sbom.cdx.json").write_text("tampered")
            with self.assertRaises(ValueError):
                verify_files(root, manifest, "a" * 40)

    def test_tampered_archive_prevents_all_docker_operations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = {"revision": "a" * 40, "repository": "owner/repo", "images": {}}
            for module in MODULES:
                (root / module).mkdir()
                for filename in ("image.tar", "sbom.cdx.json"):
                    (root / module / filename).write_text("original")
                manifest["images"][module] = {"sha256": {name: digest(root / module / name)
                                                       for name in ("image.tar", "sbom.cdx.json")}}
            (root / "manifest.json").write_text(json.dumps(manifest))
            (root / MODULES[1] / "image.tar").write_text("tampered")
            with patch("sys.argv", ["image_bundle", "publish", "--revision", "a" * 40,
                                    "--repository", "owner/repo", "--directory", directory]), \
                    patch("image_bundle.subprocess.run") as docker, \
                    patch("image_bundle.subprocess.check_output") as docker_inspect:
                with self.assertRaises(ValueError):
                    main()
                docker.assert_not_called()
                docker_inspect.assert_not_called()


if __name__ == "__main__":
    unittest.main()
