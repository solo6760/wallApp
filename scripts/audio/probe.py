#!/usr/bin/env python3
"""Compile AudioController against exact versions, optionally probe YouTube on the JVM.

Requires Java, an Android 36.1 SDK, and a cached Kotlin 2.2.10 compiler (or flags
below). Downloads dependencies into a temporary directory; changes no build files.
Network: Google Maven, Maven Central, JitPack, and (--network) YouTube/CDN hosts.
Run: python3 scripts/audio/probe.py [--network] [--url HTTPS_URL]
This is not an Android build or a device/audio-focus test.
"""
import argparse
import concurrent.futures
import glob
import io
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--network', action='store_true', help='Also attempt real stream extraction')
    parser.add_argument('--url', default='https://www.youtube.com/watch?v=rFZHOHl-L8A')
    parser.add_argument('--android-jar', default=str(Path.home() / 'Android/Sdk/platforms/android-36.1/android.jar'))
    parser.add_argument('--gradle-cache', default=str(Path.home() / '.gradle/caches/modules-2/files-2.1'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    cache = Path(args.gradle_cache)

    def cached(group, artifact, version):
        matches = glob.glob(str(cache / group / artifact / version / '*' / '*.jar'))
        matches = [p for p in matches if not p.endswith(('-sources.jar', '-javadoc.jar'))]
        if not matches:
            raise RuntimeError(f'Missing cached compiler dependency {group}:{artifact}:{version}')
        return matches[0]

    compiler = [cached('org.jetbrains.kotlin', a, '2.2.10') for a in
                ['kotlin-compiler-embeddable', 'kotlin-stdlib', 'kotlin-script-runtime']]
    compiler += [cached('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                 cached('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
                 cached('org.jetbrains', 'annotations', '13.0')]
    if not Path(args.android_jar).is_file():
        raise RuntimeError('Android 36.1 android.jar not found; pass --android-jar')

    google = 'https://dl.google.com/dl/android/maven2/'
    central = 'https://repo.maven.apache.org/maven2/'
    jitpack = 'https://jitpack.io/'
    artifacts = []

    def artifact(repo, group, name, version, extension='jar'):
        artifacts.append((f'{name}-{version}.jar', repo + group.replace('.', '/') +
                          f'/{name}/{version}/{name}-{version}.{extension}', extension))

    for name in ['exoplayer', 'exoplayer-hls', 'exoplayer-dash', 'common', 'container',
                 'datasource', 'decoder', 'extractor', 'database']:
        artifact(google, 'androidx.media3', 'media3-' + name, '1.11.1', 'aar')
    artifact(google, 'androidx.annotation', 'annotation-jvm', '1.9.1')
    artifact(jitpack, 'com.github.TeamNewPipe', 'NewPipeExtractor', 'v0.26.5')
    artifact(jitpack, 'com.github.TeamNewPipe', 'nanojson', 'e9d656ddb49a412a5a0a5d5ef20ca7ef09549996')
    for group, name, version in [
        ('org.jsoup', 'jsoup', '1.22.2'),
        ('com.google.code.findbugs', 'jsr305', '3.0.2'),
        ('com.google.protobuf', 'protobuf-javalite', '4.35.1'),
        ('org.mozilla', 'rhino', '1.8.1'), ('org.mozilla', 'rhino-engine', '1.8.1'),
        ('com.squareup.okhttp3', 'okhttp', '4.12.0'),
        ('com.squareup.okio', 'okio-jvm', '3.6.0'),
        ('com.google.guava', 'guava', '33.3.1-android'),
        ('com.google.guava', 'failureaccess', '1.0.2'),
    ]:
        artifact(central, group, name, version)

    with tempfile.TemporaryDirectory(prefix='ambientflow-audio-') as temp:
        temp = Path(temp)

        def download(item):
            name, url, extension = item
            with urllib.request.urlopen(url, timeout=60) as response:
                data = response.read()
            if extension == 'aar':
                data = zipfile.ZipFile(io.BytesIO(data)).read('classes.jar')
            path = temp / name
            path.write_bytes(data)
            return str(path)

        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
            jars = list(pool.map(download, artifacts))
        classpath = os.pathsep.join(jars + [args.android_jar] + compiler[1:])
        output = temp / 'classes'
        command = ['java', '-cp', os.pathsep.join(compiler),
                   'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                   '-jvm-target', '17', '-classpath', classpath, '-d', str(output),
                   str(root / 'app/src/main/java/org/ambientflow/tv/AudioController.kt'),
                   str(root / 'scripts/audio/ExtractorProbe.kt')]
        subprocess.run(command, check=True, timeout=120)
        print('COMPILE_OK Android 36.1 / Media3 1.11.1 / NewPipe v0.26.5 / OkHttp 4.12.0', flush=True)
        if args.network:
            proxy_options = []
            proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy', '')))
            if proxy.hostname:
                # OkHttp uses the JVM proxy selector, not HTTPS_PROXY directly.
                for scheme in ['http', 'https']:
                    proxy_options += [f'-D{scheme}.proxyHost={proxy.hostname}',
                                      f'-D{scheme}.proxyPort={proxy.port or 80}']
            subprocess.run(['java'] + proxy_options + ['-cp', str(output) + os.pathsep + classpath,
                           'org.ambientflow.tv.ExtractorProbeKt', args.url], check=True, timeout=120)


if __name__ == '__main__':
    main()
