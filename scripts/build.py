#!/usr/bin/env python3
"""Run Gradle with a project-local cache and Java-compatible proxy settings."""
import os
from pathlib import Path
import subprocess
import sys
from urllib.parse import urlsplit

root = Path(__file__).resolve().parent.parent
env = os.environ.copy()
env.setdefault("JAVA_HOME", "/home/adithya/.local/share/PrismLauncher/java/java-runtime-epsilon")
env.setdefault("ANDROID_HOME", "/home/adithya/Android/Sdk")
env.setdefault("GRADLE_USER_HOME", str(root / ".gradle-home"))
env.setdefault("ANDROID_USER_HOME", str(Path(env["GRADLE_USER_HOME"]) / "android"))
Path(env["ANDROID_USER_HOME"]).mkdir(parents=True, exist_ok=True)
env["PATH"] = str(Path(env["JAVA_HOME"]) / "bin") + os.pathsep + env.get("PATH", "")
proxy_args = []
for protocol in ("http", "https"):
    value = env.get(protocol.upper() + "_PROXY") or env.get(protocol + "_proxy")
    if value:
        proxy = urlsplit(value)
        if not proxy.hostname:
            raise SystemExit(f"Invalid {protocol.upper()}_PROXY URL")
        proxy_args.extend([
            f"-D{protocol}.proxyHost={proxy.hostname}",
            f"-D{protocol}.proxyPort={proxy.port or (443 if proxy.scheme == 'https' else 80)}",
        ])
# The wrapper JVM also needs these properties before it downloads Gradle.
env["JAVA_TOOL_OPTIONS"] = " ".join(filter(None, [env.get("JAVA_TOOL_OPTIONS", ""), *proxy_args]))
bootstrap = env.get("GRADLE_BOOTSTRAP")
command = [bootstrap] if bootstrap else [str(root / "gradlew")]
raise SystemExit(subprocess.call(command + ["--no-daemon", "--console=plain"] + sys.argv[1:], cwd=root, env=env))
