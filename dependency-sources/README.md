# Runtime dependency sources (2026-10-20)

Source-only inputs for the parent's personal-APK source ZIP; **not a standalone
app source bundle or a certification of complete corresponding source**. No APKs,
compiled dependency JARs/AARs, SDKs, or Gradle caches are included. Files named
`*-sources.jar` are ZIP containers of source, not executable dependency JARs.

## Provenance and verification

- Resolution: `../docs/runtime-dependencies.txt`, captured with
  `python3 scripts/build.py --offline :app:dependencies --configuration debugRuntimeClasspath`.
  This command succeeded; the Android metrics warning is retained verbatim.
  No assemble/build/test task was run for this collection.
- `provenance.json` lists every selected coordinate, exact download URL, status,
  archive name, byte count and SHA-256. Published source JARs come from Maven
  Central (`repo.maven.apache.org/maven2`), Google Maven
  (`dl.google.com/dl/android/maven2`), or JitPack (`jitpack.io`). They are unmodified.
- GPL NewPipeExtractor **v0.26.5** and nanojson commit
  **e9d656ddb49a412a5a0a5d5ef20ca7ef09549996** have both exact JitPack source JARs
  and tagged/commit GitHub source ZIPs, including upstream build files and notices.
- MPL Rhino **1.8.1** and `rhino-engine` **1.8.1** have exact Maven Central source
  JARs. Preserve their source headers and the full terms in `../THIRD_PARTY_NOTICES.md`.
- Desugar/OpenJDK source ZIP is from immutable commit
  **73170c345e6a762fc6a1f0301bb15218850023ef**:
  <https://github.com/google/desugar_jdk_libs/commit/73170c345e6a762fc6a1f0301bb15218850023ef>.
  Upstream commit message explicitly prepares **2.1.5**;
  `VERSION_JDK11_NIO.txt` says **2.1.5**. The ZIP includes JDK11/NIO/addon source,
  BUILD files, GPL/Classpath Exception and additional upstream notices.
- Supplemental GitHub ZIPs are repacked to omit compiled entries; provenance
  records both original-download and resulting ZIP hashes and every omission.
  Only NewPipe's `gradle/wrapper/gradle-wrapper.jar` was omitted. No source edits.
- `fetch_sources.py` reproduces downloads from the saved report without Gradle.
  It requires network access. It also refreshes `SHA256SUMS`; review any changed
  bytes rather than treating a refreshed checksum as independent authentication.
  Verify locally: `cd dependency-sources && sha256sum -c SHA256SUMS`.
  SHA-256 establishes local integrity, not upstream signatures or reproducible builds.

## Exceptions / remaining blockers

- Kotlin `kotlin-stdlib-common:2.2.10` is metadata-only: its published
  [Gradle module](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-common/2.2.10/kotlin-stdlib-common-2.2.10.module)
  delegates to `kotlin-stdlib:2.2.10`, whose sources are included. There is no
  separate source JAR. JDK7/JDK8 compatibility sources are **1.9.10**, as resolved.
- `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava` is an empty conflict
  placeholder with no published source JAR. The actual interface source is in
  included `guava:33.3.1-android` sources; do not substitute listenablefuture 1.0.
- Google Maven returns HTTP 404 for both desugar **2.1.5** source classifiers.
  The upstream release-preparation commit is strong version evidence but its
  correspondence to the published runtime binary has **not** been independently
  proven by a rebuild. No 2.1.5 GitHub release tag was found.
- **Unresolved source gap:** `desugar_jdk_libs_configuration_nio:2.1.5` includes
  R8-generated configuration and conversion/helper classes, not only metadata.
  Its exact R8 source revision/build inputs have not been established or bundled.
  The OpenJDK source ZIP alone must not be represented as covering those helpers.
  Resolve that provenance/source gap before claiming complete corresponding source
  for redistribution. Do not copy its compiled JAR into this source collection.
- Parent packaging must add exact app source/build scripts/resources/licenses and
  assess final APK contents, upstream build prerequisites and installation needs.
  Nothing here proves an offline rebuild or full GPL/MPL compliance on its own.
