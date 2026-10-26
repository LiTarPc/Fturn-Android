"""Publish the exact Fturn 4.3.0 artifact verified on the connected phone."""
import hashlib
import json
import os
from pathlib import Path
import urllib.parse
import urllib.request
import zipfile

REPO = "LiTarPc/Fturn-Android"
TAG = "v4.3.0"
RUN = 40230394567
TESTED_COMMIT = "65745901de92e368817982de85f3a7e18e2dab20"
RELEASE_COMMIT = "94df48eba2bd9b68e44060b514f10c5e66f62919"
DRAFT = 423648594
APK_SHA = "3c9ab1bef6cfb677d0a6d42951c7dea70aa0d758f6fb8e7a2b05b32a9f8c7bef"
TOKEN = os.environ["GH_TOKEN"]

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(NoRedirect)
def api(path, method="GET", data=None, upload=False):
    if upload:
        assert path.startswith("https://uploads.github.com/repos/" + REPO + "/releases/" + str(DRAFT) + "/assets?")
        url, payload, content_type = path, data, "application/octet-stream"
    else:
        assert path.startswith("/repos/" + REPO + "/")
        url = "https://api.github.com" + path
        payload = json.dumps(data).encode() if data is not None else None
        content_type = "application/json"
    request = urllib.request.Request(url, method=method, data=payload, headers={
        "Authorization": "Bearer " + TOKEN, "Accept": "application/vnd.github+json",
        "Content-Type": content_type, "User-Agent": "Fturn-verified-release",
        "X-GitHub-Api-Version": "2022-11-28"})
    with opener.open(request, timeout=180) as response:
        body = response.read()
        return json.loads(body) if body else None

run = api("/repos/" + REPO + "/actions/runs/" + str(RUN))
assert run["conclusion"] == "success" and run["head_sha"] == TESTED_COMMIT
root = Path("dist")
expected = {}
for line in (root / "SHA256SUMS.txt").read_text().splitlines():
    digest, name = line.split(maxsplit=1)
    name = name.lstrip("* ")
    assert Path(name).name == name
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
    expected[name] = digest
assert expected.get("fturn-4.3.0-arm64-v8a.apk") == APK_SHA
assert len(expected) == 7
with zipfile.ZipFile(root / "fturn-4.3.0-arm64-v8a.apk") as archive:
    for name, binary, version in [("freeturn", "libfreeturn.so", "4.2.4-test.1"), ("raw", "libfturnraw.so", "1.0.1-beta")]:
        embedded = json.loads(archive.read("assets/" + name + "-core.json"))
        pin = json.loads(Path("third_party/" + name + "-core/SOURCE.json").read_text())
        assert embedded["version"] == pin["coreVersion"] == version
        assert embedded["sourceSnapshotSha256"] == pin["sourceSnapshotSha256"]
        assert embedded["binarySha256"] == hashlib.sha256(archive.read("lib/arm64-v8a/" + binary)).hexdigest()
base = "/repos/" + REPO + "/releases/" + str(DRAFT)
release = api(base)
assert release["tag_name"] == TAG and release["draft"]
assert release["target_commitish"] == RELEASE_COMMIT
assets = {a["name"]: a for a in api(base + "/assets")}
for name in list(expected) + ["SHA256SUMS.txt"]:
    payload = (root / name).read_bytes()
    digest = "sha256:" + hashlib.sha256(payload).hexdigest()
    existing = assets.get(name)
    if existing:
        if existing.get("digest") == digest:
            print("Verified existing asset:", name, flush=True)
            continue
        # Only an empty, failed upload in this draft may be replaced.
        assert existing["state"] == "starter" and existing["size"] == 0
        api("/repos/" + REPO + "/releases/assets/" + str(existing["id"]), "DELETE")
    endpoint = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(name)
    uploaded = api(endpoint, "POST", payload, upload=True)
    assert uploaded["digest"] == digest, name
    print("Uploaded and verified:", name, flush=True)
# Publish only after every remote asset has the exact expected digest.
remote = {a["name"]: a for a in api(base + "/assets")}
for name, digest in expected.items():
    assert remote[name]["digest"] == "sha256:" + digest
assert remote["SHA256SUMS.txt"]["digest"] == "sha256:" + hashlib.sha256((root / "SHA256SUMS.txt").read_bytes()).hexdigest()
release = api(base, "PATCH", {"draft": False, "prerelease": False, "make_latest": "true"})
assert not release["draft"] and not release["prerelease"]
assert api("/repos/" + REPO + "/releases/latest")["id"] == DRAFT
print("Published:", release["html_url"], flush=True)
