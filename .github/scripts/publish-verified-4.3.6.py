"""Publish the exact Fturn 4.3.6 artifact tested on the connected Android phone."""
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.parse
import urllib.request
import urllib.error
import zipfile

REPO = "LiTarPc/Fturn-Android"
TAG = "v4.3.6"
RUN = 40860085779
ARTIFACT = 12892625048
TESTED_COMMIT = "afe29a87ce3da2b4e001f0fc9de7942f57031145"
RELEASE_COMMIT = os.environ["GITHUB_SHA"]
assert re.fullmatch(r"[0-9a-f]{40}", RELEASE_COMMIT)
DRAFT = None
APK = "fturn-4.3.6-arm64-v8a.apk"
APK_SHA = "8f4ffa6e1a714119674a21575f6fcf9b1832ff892af258bab34ec4126af110d7"
FILES = {
    APK,
    "fturn-freeturn-core-4.2.4-test.1-android-source.tar.gz",
    "fturn-raw-core-1.0.1-beta.android.4-android-source.tar.gz",
    "freeturn-core-4.2.4-test.1.json",
    "raw-core-1.0.1-beta.android.4.json",
    "RAW-CORE-THIRD-PARTY-NOTICES.txt",
    "FREETURN-CORE-LICENSE.txt",
}
TOKEN = os.environ["GH_TOKEN"]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(NoRedirect)


def api(path, method="GET", data=None, upload=False):
    if upload:
        assert path.startswith(f"https://uploads.github.com/repos/{REPO}/releases/{DRAFT}/assets?")
        url, payload, content_type = path, data, "application/octet-stream"
    else:
        assert path.startswith(f"/repos/{REPO}/")
        url = "https://api.github.com" + path
        payload = json.dumps(data).encode() if data is not None else None
        content_type = "application/json"
    request = urllib.request.Request(url, method=method, data=payload, headers={
        "Authorization": "Bearer " + TOKEN,
        "Accept": "application/vnd.github+json",
        "Content-Type": content_type,
        "User-Agent": "Fturn-verified-release",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    with opener.open(request, timeout=180) as response:
        body = response.read()
        return json.loads(body) if body else None


run = api(f"/repos/{REPO}/actions/runs/{RUN}")
assert run["conclusion"] == "success" and run["head_sha"] == TESTED_COMMIT
artifact = api(f"/repos/{REPO}/actions/artifacts/{ARTIFACT}")
assert not artifact["expired"] and artifact["name"] == "fturn-signed-4.3.6"
assert artifact["workflow_run"]["id"] == RUN
assert artifact["workflow_run"]["head_sha"] == TESTED_COMMIT
assert artifact["digest"] == "sha256:755abc739d2e23b94c70d1eb0e8458a88897dcb1083ba5a9578430b9dd563a68"

root = Path("dist")
expected = {}
for line in (root / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
    digest, name = line.split(maxsplit=1)
    name = name.lstrip("* ")
    assert re.fullmatch(r"[0-9a-f]{64}", digest)
    assert Path(name).name == name and name in FILES and name not in expected
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, name
    expected[name] = digest
assert set(expected) == FILES and expected[APK] == APK_SHA
with zipfile.ZipFile(root / APK) as archive:
    for name, binary, version in [
        ("freeturn", "libfreeturn.so", "4.2.4-test.1"),
        ("raw", "libfturnraw.so", "1.0.1-beta.android.4"),
    ]:
        embedded = json.loads(archive.read(f"assets/{name}-core.json"))
        pin = json.loads(Path(f"third_party/{name}-core/SOURCE.json").read_text(encoding="utf-8"))
        provenance = json.loads((root / f"{name}-core-{version}.json").read_text(encoding="utf-8"))
        assert provenance == embedded
        assert embedded["version"] == pin["coreVersion"] == version
        assert embedded["sourceSnapshotSha256"] == pin["sourceSnapshotSha256"]
        assert embedded["binarySha256"] == hashlib.sha256(archive.read(f"lib/arm64-v8a/{binary}")).hexdigest()

# Create the draft only after the exact signed artifacts have passed verification.
# GITHUB_TOKEN publication does not trigger an independent tag rebuild workflow.
try:
    release = api(f"/repos/{REPO}/releases/tags/{TAG}")
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
    release = api(f"/repos/{REPO}/releases", "POST", {
        "tag_name": TAG,
        "target_commitish": RELEASE_COMMIT,
        "name": "Fturn 4.3.6",
        "body": Path("docs/releases/v4.3.6.md").read_text(encoding="utf-8"),
        "draft": True,
        "prerelease": False,
        "make_latest": "false",
    })
DRAFT = release["id"]
base = f"/repos/{REPO}/releases/{DRAFT}"
assert release["tag_name"] == TAG and not release["prerelease"]
assert release["target_commitish"] == RELEASE_COMMIT
assets = {a["name"]: a for a in api(base + "/assets")}
expected["SHA256SUMS.txt"] = hashlib.sha256((root / "SHA256SUMS.txt").read_bytes()).hexdigest()
assert not (set(assets) - set(expected)), "Unexpected release assets"
for name, sha in expected.items():
    payload = (root / name).read_bytes()
    digest = "sha256:" + sha
    existing = assets.get(name)
    if existing:
        if existing.get("digest") == digest and existing["size"] == len(payload):
            print("Verified existing asset:", name, flush=True)
            continue
        # Only an empty failed upload in this draft may be replaced.
        assert release["draft"] and existing["state"] == "starter" and existing["size"] == 0
        api(f"/repos/{REPO}/releases/assets/{existing['id']}", "DELETE")
    assert release["draft"], "Never modify assets of an already published release"
    endpoint = release["upload_url"].split("{")[0] + "?name=" + urllib.parse.quote(name)
    uploaded = api(endpoint, "POST", payload, upload=True)
    assert uploaded["digest"] == digest and uploaded["size"] == len(payload), name
    print("Uploaded and verified:", name, flush=True)

# Publication is the last mutation, after all eight remote files are verified.
remote = {a["name"]: a for a in api(base + "/assets")}
assert set(remote) == set(expected)
for name, digest in expected.items():
    assert remote[name]["state"] == "uploaded"
    assert remote[name]["digest"] == "sha256:" + digest
    assert remote[name]["size"] == (root / name).stat().st_size
if release["draft"]:
    release = api(base, "PATCH", {"draft": False, "prerelease": False, "make_latest": "true"})
assert not release["draft"] and not release["prerelease"]
assert api(f"/repos/{REPO}/releases/latest")["id"] == DRAFT
tag = api(f"/repos/{REPO}/git/ref/tags/{TAG}")
assert tag["object"]["type"] == "commit" and tag["object"]["sha"] == RELEASE_COMMIT
print("Published:", release["html_url"], flush=True)
