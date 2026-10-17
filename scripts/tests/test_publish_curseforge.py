import contextlib
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from zipfile import ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_curseforge as publisher


class PublishingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.release = {"tagName": "v0.5.0-alpha.2", "body": "Fix target changes.\nSupport more Minecraft versions.", "isDraft": False}

    def jar(self, target, version="0.5.0-alpha.2", mod_id="localchattranslate"):
        path = self.root / f"localchattranslate-{version}+{target}.jar"
        with ZipFile(path, "w") as archive:
            archive.writestr("fabric.mod.json", json.dumps({
                "id": mod_id, "environment": "client", "version": f"{version}+{target}",
                "depends": {"minecraft": target},
            }))
        return path

    def test_each_jar_has_its_own_target_and_dependencies(self):
        self.jar("26.1")
        self.jar("26.2")
        uploads = publisher.prepare_uploads(self.root, self.release)
        self.assertEqual([m["gameVersionNames"] for _, m in uploads],
                         [["26.1", "Fabric", "Client"], ["26.2", "Fabric", "Client"]])
        for _, metadata in uploads:
            self.assertEqual(metadata["releaseType"], "alpha")
            self.assertEqual(metadata["changelog"], self.release["body"])
            self.assertIn({"slug": "fabric-api", "type": "requiredDependency"}, metadata["relations"]["projects"])
        self.assertEqual(len(publisher.prepare_uploads(self.root, self.release, "26.2")), 1)
        with self.assertRaises(ValueError):
            publisher.prepare_uploads(self.root, self.release, "26.3")

    def test_stable_and_release_candidate_channels(self):
        for version, expected in [("1.0.0", "release"), ("1.0.0-rc.1", "beta")]:
            with self.subTest(version=version):
                jar = self.jar("26.2", version)
                release = dict(self.release, tagName="v" + version)
                self.assertEqual(publisher.prepare_uploads(self.root, release)[0][1]["releaseType"], expected)
                jar.unlink()

    def test_invalid_release_is_rejected_before_uploading(self):
        self.jar("26.1")
        self.jar("26.2", version="0.4.0")
        with self.assertRaisesRegex(ValueError, "disagree"):
            publisher.prepare_uploads(self.root, self.release)
        for release in [dict(self.release, body=""), dict(self.release, isDraft=True), dict(self.release, tagName="v1;bad")]:
            with self.subTest(release=release), self.assertRaises(ValueError):
                publisher.prepare_uploads(self.root, release)

    def test_dry_run_needs_no_credentials_and_makes_no_request(self):
        self.jar("26.1")
        release = self.root / "release.json"
        release.write_text(json.dumps(self.release), encoding="utf-8")
        args = ["--release-json", str(release), "--files-dir", str(self.root)]
        with patch.dict(os.environ, {}, clear=True), patch.object(publisher, "upload") as upload:
            with contextlib.redirect_stdout(io.StringIO()) as output:
                publisher.main(args + ["--dry-run"])
            self.assertIn('"26.1"', output.getvalue())
            with self.assertRaisesRegex(ValueError, "CURSEFORGE_PROJECT_ID"):
                publisher.main(args)
            upload.assert_not_called()

    def test_real_multipart_request_uses_header_authentication(self):
        jar = self.jar("26.2")
        metadata = publisher.prepare_uploads(self.root, self.release)[0][1]
        captured = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                captured.update(path=self.path, token=self.headers.get("X-Api-Token"))
                data = self.rfile.read(int(self.headers["Content-Length"]))
                mime = ("Content-Type: " + self.headers["Content-Type"] + "\r\nMIME-Version: 1.0\r\n\r\n").encode() + data
                captured["parts"] = list(BytesParser(policy=policy.default).parsebytes(mime).iter_parts())
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"id": 12345}')

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with patch.object(publisher, "API_ROOT", f"http://127.0.0.1:{server.server_port}/api"):
                    self.assertEqual(publisher.upload(jar, metadata, "123", "test-upload-token"), 12345)
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(captured["path"], "/api/projects/123/upload-file")
        self.assertEqual(captured["token"], "test-upload-token")
        fields = {p.get_param("name", header="content-disposition"): p for p in captured["parts"]}
        self.assertEqual(json.loads(fields["metadata"].get_payload(decode=True)), metadata)
        self.assertEqual(fields["file"].get_filename(), jar.name)
        self.assertEqual(fields["file"].get_payload(decode=True), jar.read_bytes())

    def test_upload_errors_are_not_retried_or_disclosed_and_redirects_are_blocked(self):
        jar = self.jar("26.2")
        metadata = publisher.prepare_uploads(self.root, self.release)[0][1]
        requests = []
        response_code = 302

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                requests.append(self.path)
                self.rfile.read(int(self.headers["Content-Length"]))
                self.send_response(response_code)
                self.send_header("Location", "/redirected")
                self.end_headers()
                self.wfile.write(b'sensitive-response-and-token')

            def do_GET(self):
                requests.append(self.path)
                self.send_response(200)
                self.end_headers()

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with patch.object(publisher, "API_ROOT", f"http://127.0.0.1:{server.server_port}/api"):
                    for response_code in (302, 500, 200):
                        with self.subTest(status=response_code), self.assertRaises(RuntimeError) as result:
                            publisher.upload(jar, metadata, "123", "sensitive-response-and-token")
                        self.assertNotIn("sensitive", str(result.exception))
            finally:
                server.shutdown()
                thread.join()
        self.assertEqual(requests, ["/api/projects/123/upload-file"] * 3)


if __name__ == "__main__":
    unittest.main()
