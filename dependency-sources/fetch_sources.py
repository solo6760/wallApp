#!/usr/bin/env python3
"""Fetch source-only archives for the saved runtime report; never runs Gradle."""
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import re

import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
REPORT = ROOT.parent / "docs/runtime-dependencies.txt"


def coordinates():
    result = set()
    for line in REPORT.read_text().splitlines():
        if " (c)" in line:
            continue
        match = re.search(r"(?:\+---|\\---) ([\w.\-]+):([\w.\-]+):([^\s]+)(?: -> ([^\s]+))?", line)
        if match:
            group, artifact, requested, selected = match.groups()
            result.add((group, artifact, selected or requested))
    # Core library desugaring is outside debugRuntimeClasspath. This version is
    # explicitly configured in app/build.gradle.kts and present in the local cache.
    result.add(("com.android.tools", "desugar_jdk_libs_nio", "2.1.5"))
    result.add(("com.android.tools", "desugar_jdk_libs_configuration_nio", "2.1.5"))
    return sorted(result)


def fetch(coordinate):
    group, artifact, version = coordinate
    name = f"{artifact}-{version}-sources.jar"
    repository = ("https://dl.google.com/dl/android/maven2" if group.startswith("androidx.") or group == "com.android.tools"
                  else "https://jitpack.io" if group.startswith("com.github.")
                  else "https://repo.maven.apache.org/maven2")
    url = f"{repository}/{group.replace('.', '/')}/{artifact}/{version}/{name}"
    record = {"coordinate": ":".join(coordinate), "url": url,
              "scope": "coreLibraryDesugaring" if group == "com.android.tools" else "debugRuntimeClasspath"}
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "AmbientFlow-source-bundle/1.0"})
        with urllib.request.urlopen(request, timeout=45) as response:
            data = response.read()
            record["final_url"] = response.url
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            bad = archive.testzip()
            if bad:
                raise ValueError(f"bad ZIP entry: {bad}")
            names = archive.namelist()
            record["source_files"] = sum(n.endswith((".java", ".kt", ".kts", ".cc", ".cpp", ".h")) for n in names)
            binaries = [n for n in names if n.endswith((".class", ".dex", ".so"))]
            if binaries:
                raise ValueError(f"unexpected binary entries: {binaries[:3]}")
        path = ROOT / name
        path.write_bytes(data)
        record.update(file=name, sha256=hashlib.sha256(data).hexdigest(), bytes=len(data), status="downloaded")
        print(f"OK {record['coordinate']} ({record['source_files']} source files)", flush=True)
    except Exception as exc:
        record.update(status="unavailable", error=str(exc))
        print(f"GAP {record['coordinate']}: {exc}", flush=True)
    return record


def upstream_archive(name, url, components):
    record = {"file": name, "url": url, "components": components}
    try:
        with urllib.request.urlopen(url, timeout=90) as response:
            original = response.read()
            record["final_url"] = response.url
        record["upstream_sha256"] = hashlib.sha256(original).hexdigest()
        omitted = []
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                if entry.filename.endswith((".jar", ".class", ".dex", ".so", ".dll", ".exe", ".a", ".o")):
                    omitted.append(entry.filename)
                else:
                    target.writestr(entry, source.read(entry))
        data = output.getvalue()
        (ROOT / name).write_bytes(data)
        record.update(status="downloaded", sha256=hashlib.sha256(data).hexdigest(),
                      bytes=len(data), omitted_binary_entries=omitted,
                      transformation="ZIP repacked retaining source/resources/notices/build files; compiled binary entries excluded")
        print(f"OK upstream {name}: {len(data)} bytes; omitted {len(omitted)} binaries", flush=True)
    except Exception as exc:
        record.update(status="unavailable", error=str(exc))
        print(f"GAP upstream {name}: {exc}", flush=True)
    return record


def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        records = list(pool.map(fetch, coordinates()))
    supplements = [
        ("NewPipeExtractor-v0.26.5-upstream-source.zip",
         "https://codeload.github.com/TeamNewPipe/NewPipeExtractor/zip/refs/tags/v0.26.5",
         ["com.github.TeamNewPipe:NewPipeExtractor:v0.26.5"]),
        ("nanojson-e9d656ddb49a412a5a0a5d5ef20ca7ef09549996-upstream-source.zip",
         "https://codeload.github.com/TeamNewPipe/nanojson/zip/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996",
         ["com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996"]),
        ("desugar_jdk_libs-2.1.5-73170c345e6a762fc6a1f0301bb15218850023ef-source.zip",
         "https://codeload.github.com/google/desugar_jdk_libs/zip/73170c345e6a762fc6a1f0301bb15218850023ef",
         ["com.android.tools:desugar_jdk_libs_nio:2.1.5"]),
    ]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        upstream = list(pool.map(lambda args: upstream_archive(*args), supplements))
    for record in records:
        coordinate = record["coordinate"]
        if coordinate == "org.jetbrains.kotlin:kotlin-stdlib-common:2.2.10":
            record.update(status="metadata-only", covered_by="kotlin-stdlib-2.2.10-sources.jar",
                          note="Published Gradle module contains no binary, only a dependency on kotlin-stdlib 2.2.10.",
                          metadata_url="https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-common/2.2.10/kotlin-stdlib-common-2.2.10.module")
        elif coordinate == "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava":
            record.update(status="empty-artifact", covered_by="guava-33.3.1-android-sources.jar",
                          note="Empty conflict-avoidance artifact; actual ListenableFuture source is in Guava. No source classifier is published.")
        elif coordinate == "com.android.tools:desugar_jdk_libs_nio:2.1.5" and upstream[2]["status"] == "downloaded":
            record.update(status="upstream-source-candidate", covered_by=upstream[2]["file"],
                          note="Immutable upstream commit explicitly prepares 2.1.5; no source classifier or release tag. Maven binary correspondence has not been independently proven.")
        elif coordinate == "com.android.tools:desugar_jdk_libs_configuration_nio:2.1.5":
            record.update(note="No source classifier. Companion configuration generation lives in R8; exact configuration source revision not established. See README.md blocker.")
    (ROOT / "provenance.json").write_text(json.dumps({"artifacts": records, "upstream_archives": upstream}, indent=2) + "\n")
    sums = [f"{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n"
            for p in sorted(ROOT.iterdir()) if p.is_file() and p.name != "SHA256SUMS"]
    (ROOT / "SHA256SUMS").write_text("".join(sums))


if __name__ == "__main__":
    main()
