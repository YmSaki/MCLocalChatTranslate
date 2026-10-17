"""Publish validated GitHub release JARs through the CurseForge Upload API."""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import urllib.error
import urllib.request
import uuid
from zipfile import ZipFile


API_ROOT = "https://minecraft.curseforge.com/api"


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def prepare_uploads(directory, release, minecraft=None):
    tag = release.get("tagName", "")
    if not re.fullmatch(r"v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", tag):
        raise ValueError("Release tag must look like v0.5.0-alpha.2, without a Minecraft suffix")
    if release.get("isDraft"):
        raise ValueError("Publish the GitHub release before uploading to CurseForge")
    changelog = release.get("body", "").strip()
    if not changelog:
        raise ValueError("The GitHub release must have a changelog")
    version = tag[1:]
    release_type = "alpha" if "-alpha" in version else "beta" if "-" in version else "release"
    uploads = []
    seen = set()
    for jar in sorted(Path(directory).glob("*.jar")):
        with ZipFile(jar) as archive:
            mod = json.loads(archive.read("fabric.mod.json"))
        if mod.get("id") != "localchattranslate" or mod.get("environment") != "client":
            raise ValueError(f"Unexpected mod or environment in {jar.name}")
        target = mod.get("depends", {}).get("minecraft", "")
        if not isinstance(target, str) or not re.fullmatch(r"\d+\.\d+(?:\.\d+)?", target):
            raise ValueError(f"Expected one exact Minecraft version in {jar.name}")
        expected_version = f"{version}+{target}"
        if mod.get("version") != expected_version or jar.name != f"localchattranslate-{expected_version}.jar":
            raise ValueError(f"Release tag, JAR name and mod version disagree: {jar.name}")
        if target in seen:
            raise ValueError(f"Multiple release JARs for Minecraft {target}")
        seen.add(target)
        if minecraft and target != minecraft:
            continue
        metadata = {
            "changelog": changelog,
            "changelogType": "markdown",
            "displayName": f"LocalChatTranslate {version} - Minecraft {target} (Fabric)",
            "gameVersionNames": [target, "Fabric", "Client"],
            "releaseType": release_type,
            "relations": {"projects": [
                {"slug": "fabric-api", "type": "requiredDependency"},
                {"slug": "modmenu", "type": "optionalDependency"},
            ]},
        }
        uploads.append((jar, metadata))
    if not uploads:
        raise ValueError("No matching release JARs found")
    return uploads


def upload(jar, metadata, project_id, token):
    boundary = "lct-" + uuid.uuid4().hex
    body = (
        f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n'
        'Content-Type: application/json\r\n\r\n'
    ).encode() + json.dumps(metadata).encode() + (
        f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{jar.name}"\r\n'
        'Content-Type: application/java-archive\r\n\r\n'
    ).encode() + jar.read_bytes() + f"\r\n--{boundary}--\r\n".encode()
    request = urllib.request.Request(
        f"{API_ROOT}/projects/{project_id}/upload-file", data=body, method="POST",
        headers={"X-Api-Token": token, "Content-Type": f"multipart/form-data; boundary={boundary}",
                 "User-Agent": "LocalChatTranslate-release-ci"},
    )
    try:
        with urllib.request.build_opener(NoRedirects()).open(request, timeout=180) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        # Do not echo response bodies or request headers that could contain credentials.
        raise RuntimeError(f"CurseForge returned HTTP {error.code}; check the project and upload token") from None
    except (OSError, ValueError) as error:
        raise RuntimeError("Upload response was not received or was invalid; check CurseForge before retrying") from None
    file_id = result.get("id") if isinstance(result, dict) else None
    if type(file_id) is not int or file_id <= 0:
        raise RuntimeError("CurseForge did not return a file ID; check CurseForge before retrying")
    return file_id


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-json", type=Path, required=True)
    parser.add_argument("--files-dir", type=Path, required=True)
    parser.add_argument("--minecraft", help="Publish only this Minecraft version")
    parser.add_argument("--dry-run", action="store_true", help="Validate and print metadata without a token or network requests")
    args = parser.parse_args(argv)
    release = json.loads(args.release_json.read_text(encoding="utf-8"))
    # Validate every file before the first upload to avoid partially publishing an invalid release.
    uploads = prepare_uploads(args.files_dir, release, args.minecraft)
    project_id = os.environ.get("CURSEFORGE_PROJECT_ID", "").strip()
    token = os.environ.get("CURSEFORGE_API_TOKEN", "").strip()
    if not args.dry_run:
        if not re.fullmatch(r"[1-9]\d*", project_id):
            raise ValueError("Set the CURSEFORGE_PROJECT_ID repository variable to the numeric project ID")
        if not token:
            raise ValueError("Set the CURSEFORGE_API_TOKEN repository secret to an author upload token")
    for jar, metadata in uploads:
        if args.dry_run:
            print(json.dumps({"file": jar.name, "metadata": metadata}, indent=2))
        else:
            file_id = upload(jar, metadata, project_id, token)
            print(f"Uploaded {jar.name}: CurseForge file {file_id}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Publish failed: {error}", file=sys.stderr)
        sys.exit(1)
