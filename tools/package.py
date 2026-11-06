"""Package an already verified APK and portable source without SDK paths or caches."""
from pathlib import Path
import hashlib
import json
import re
import zipfile

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
dist.mkdir(exist_ok=True)
version = re.search(r'versionName\s*=\s*"([^"]+)"', (root / "app/build.gradle.kts").read_text("utf-8"))[1]
apk = dist / f"TaiAV-{version}-debug.apk"
assert apk.is_file(), "Run build.ps1 first"
verification = json.loads((root / f"artifacts/build-verification-{version}.json").read_text("utf-8-sig"))
assert verification["version"] == version
assert verification["apkSha256"] == hashlib.sha256(apk.read_bytes()).hexdigest(), "Re-run build.ps1 -Verify for this APK"
assert set(verification["checks"]) >= {"assembleDebug", "lintDebug", ":network:checkNetwork"}
dom = json.loads((root / "artifacts/ad-filter-dom.json").read_text("utf-8"))
assert dom["success"], "Run node tools/ad_filter_checks.cjs first"
assert dom["scriptSha256"] == hashlib.sha256((root / "app/src/main/assets/ad-filter.js").read_bytes()).hexdigest()
media = json.loads((root / "artifacts/media-monitor-dom.json").read_text("utf-8"))
assert media["success"], "Run node tools/media_monitor_checks.cjs first"
assert media["scriptSha256"] == hashlib.sha256((root / "app/src/main/assets/media-monitor.js").read_bytes()).hexdigest()

archive = dist / f"TaiAV-{version}-source.zip"
excluded_parts = {".git", ".gradle", ".kotlin", ".work", "build", "dist", ".idea", "__pycache__", "node_modules"}
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if any(part in excluded_parts for part in relative.parts):
            continue
        if path.name == "local.properties" or path.name.startswith("ui-") or path.suffix in {".log", ".jks", ".keystore", ".apk"}:
            continue
        output.write(path, Path("taiav-android") / relative)

lines = []
for artifact in (apk, archive):
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    lines.append(f"{digest}  {artifact.name}")
    print(f"{artifact.name}: {artifact.stat().st_size} bytes")
(dist / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="ascii")
with zipfile.ZipFile(archive) as output:
    assert output.testzip() is None
    assert "taiav-android/gradle/wrapper/gradle-wrapper.jar" in output.namelist()
    assert "taiav-android/app/src/main/AndroidManifest.xml" in output.namelist()
print("Source archive and SHA-256 checksums verified")
