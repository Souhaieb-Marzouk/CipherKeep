# Reproducible builds

## Pinned inputs

| Input | Pin |
|---|---|
| Rust | `1.99.1` (`rust-toolchain.toml`) |
| Node | `22.22.0` (`.nvmrc`) |
| Gradle | `8.13` (wrapper) |
| Android JDK | 17 |
| Android SDK | 36 |
| Android build-tools | 36.0.0 |
| NDK | 27.2.12479018 |
| Cargo dependencies | `Cargo.lock` + `vendor/` |
| npm dependencies | `package-lock.json` + `npm ci` |

Vendored Rust hash:

```text
6d07b09ad1ab440b4013a625b2b732e49b8243f2dbf5cbb7ee5eb5115a9ab047
```

Check it:

```bash
python3 scripts/vendor-hash.py --check
```

## Build

### Rust

```bash
cargo build --locked --workspace --release
cargo test --locked --workspace
```

`.cargo/config.toml` replaces crates.io with `vendor/`. A missing registry
substitution is a build error, not a silent download.

### Firefox

```bash
cd firefox
npm ci
npm run build
npm run build:xpi
```

`pack-xpi.mjs`:

- uses `SOURCE_DATE_EPOCH=0` where supported
- sorts entries by path
- writes zero DOS timestamps
- uses fixed compression settings
- omits build metadata

The unsigned XPI should be byte-identical across clean builds with the same
lockfile.

### Android

```bash
cd android
./gradlew :app:assembleRelease --no-daemon
```

The unsigned APK is assembled from pinned Gradle/AGP/JDK/SDK inputs.

## Compare two builds

```bash
node scripts/compare-builds.mjs build-left/dist build-right/dist
```

For the generated XPI:

```bash
sha256sum build-left/firefox/web-ext-artifacts/cipherkeep-0.1.0.zip
sha256sum build-right/firefox/web-ext-artifacts/cipherkeep-0.1.0.zip
```

A difference exits non-zero and lists the differing paths.

## CI

The `reproducible` CI job:

1. checks out the same commit twice
2. runs `npm ci` in both trees
3. builds the extension and XPI twice
4. compares `dist/` with `compare-builds.mjs`
5. compares the unsigned XPI SHA-256
6. checks the vendor hash

## Known sources of nondeterminism

### Signatures

Signed APK, AAB and XPI files cannot be byte-identical:

- APK/AAB signatures contain the signing key and signature blocks
- AMO adds its own signature and may reorder ZIP metadata
- Minisign and SLSA provenance contain timestamps/build identities

Compare the unsigned content, then verify signatures separately.

### Android

- `zipalign` and `apksigner` rewrite the ZIP and add signature blocks.
- R8 can emit different mapping files when class ordering changes.
- AGP may embed build timestamps in some metadata.
- The release AAB is signed and may include a signature block.

Compare:

- unsigned release APK entries
- `classes.dex` hashes
- resource table
- the R8 mapping file
- `apksigner` certificate fingerprint

### Firefox

- AMO signs the XPI; the signed file differs from the local ZIP.
- The local `pack-xpi.mjs` output is the reproducible comparison target.
- `web-ext sign` output naming can vary; the workflow normalizes it.

### Environment

- Different JDK/Node/Rust patch versions change output.
- Different Android SDK/build-tools change resources.
- Absolute paths can appear in debug symbols; release builds strip them.
- Filesystem ordering is normalized by sorted ZIP entries.

## Clean Windows rebuild

1. Install JDK 17, Node 22.22.0, Rust 1.99.1, Android SDK 36, build-tools
   36.0.0, NDK 27.2.12479018.
2. Clone the repository at the release commit.
3. Run:
   ```powershell
   python scripts\vendor-hash.py --check
   cd firefox
   npm ci
   npm run check
   npm run build
   npm run build:xpi
   ```
4. Compare the unsigned XPI against the published one.
5. For Android:
   ```powershell
   cd android
   .\gradlew.bat :app:assembleRelease --no-daemon
   ```
6. Compare the unsigned APK entries with the published unsigned artifact.

Report any mismatch with the differing paths and tool versions.

## What cannot be compared

- signed APK/AAB/XPI bytes
- AMO’s signed XPI container
- SLSA provenance JSON
- Minisign signature bytes
- timestamps embedded by external signers

These are verified by signature/provenance checks instead.
