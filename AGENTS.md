# AGENTS.md

Guidance for AI coding agents (Claude Code, opencode, etc) working in this repository.

## What this is

Maintained fork of Android Password Store, an Android client for `pass`
(OpenPGP-encrypted files in a git repository). Upstream
(`android-password-store/Android-Password-Store`) was archived in October 2024; the fork
started in November 2026 from upstream's last commit. Kotlin, Gradle, about 18k lines.

Modules: `app` (UI, git sync, autofill service), `autofill-parser`, `crypto:common`,
`crypto:pgpainless`, `format:common`, `passgen:diceware`, `passgen:random`, `ui:compose`,
`coroutine-utils`, `sentry-stub`. Build conventions live in `build-logic/`.

The app name and `applicationId` (`app.passwordstore`) are still upstream's on purpose. Do not
rename them unless asked: it is tied to the release-signing decision (see Releases).

## Repository layout and remotes

- `master` is the only branch. Upstream's `develop` was renamed; do not recreate it.
- `origin` fetches from the maintainer's own git server (the main repository) and pushes to it
  and to the GitHub fork (`danixland/Android-Password-Store`) in one `git push`.
  GitHub is a push mirror: never create commits on GitHub (bots, web edits), they would
  diverge from the main repository.
- `upstream` is fetch-only (push URL disabled).
- Work directly on `master`, no PRs.

## Build and test

Needs JDK 17 and the Android SDK. `local.properties` (gitignored) holds `sdk.dir`.

    ./gradlew assembleFreeDebug                 # debug APK
    ./gradlew test -PslimTests                  # unit tests, as CI runs them
    ./gradlew spotlessApply                     # formatting (ktfmt); CI runs spotlessCheck
    ./gradlew lint                              # all modules and variants, as CI runs it

Prefix with `CI=1` to match CI: it turns Kotlin warnings into errors, so a deprecation warning
that passes locally fails CI. Before committing, run:

    CI=1 ./gradlew spotlessCheck test -PslimTests assembleFreeDebug assembleFreeRelease \
      assembleNonFreeDebug assembleNonFreeRelease lint metalavaCheckCompatibilityRelease

Flavors: `free` (no proprietary dependencies, Sentry replaced by `sentry-stub`) and `nonFree`
(adds Sentry and Play Services SMS OTP).

CI is `.github/workflows/pull_request.yml` (named "CI"), on push to `master`. Workflows use the
`setup-gradle` composite action from this fork at `@master`. It checks the repository out
itself, so it cannot be referenced with a local `./` path.

## End-to-end testing on an emulator

`scripts/e2e-env.sh up` starts a disposable git-over-SSH server in Docker
(`ssh://git@10.0.2.2:2222/srv/git/store.git` from the emulator) seeded with a small store
encrypted to a throwaway OpenPGP key. Keys are generated into `.e2e/` (gitignored), never
committed; `push-keys` copies them to the emulator's Download folder. `down` removes it.
Never drive tests against a real password store or with real keys.

Emulator: AVD `aps-test` (Pixel 6, AOSP Android 15 x86_64, no Google services), using the SDK
in `local.properties`:

    $ANDROID_HOME/emulator/emulator -avd aps-test -no-window -no-audio -no-boot-anim \
      -no-snapshot-save -gpu swiftshader_indirect &
    ./gradlew installFreeDebug

Drive it with `adb shell input`, `uiautomator dump` and `screencap`. Decrypt screens set
`FLAG_SECURE`, so screenshots of them are black; read them from the `uiautomator` dump.
Fingerprint: `adb emu finger touch 1` after enrolling one in Settings.

## Verified facts and gotchas

- Bouncy Castle: every `org.bouncycastle` module is forced to the catalog version by a
  resolution rule in the root `build.gradle.kts`. PGPainless and sshj pull different versions,
  and mixing them is unsupported.
- Bouncy Castle and PGPainless 1.7.8 decrypt GnuPG's OCB (LibrePGP AEAD) packets, the default
  since GnuPG 2.3. Older stacks rejected them as "non-standard AEAD"; that error path was
  removed. `crypto/pgpainless/src/test/resources/gnupg_ocb_*` are real GnuPG 2.5 fixtures.
- Test secret-key fixtures trip the maintainer's gitleaks pre-commit hook. A throwaway test key
  is allowlisted by fingerprint in `.gitleaksignore`
  (`<path>:private-key:<line>`). Never allowlist anything but generated test keys.
- `app/lint-baseline.xml` entries for library jars are keyed on the jar's cache path, version
  and hash included. A dependency bump that moves a baselined jar needs the path updated.
- `LaunchActivity` is exported and forwards `FILE_PATH` to `DecryptActivity` (not exported)
  only after `isFileInRepo` checks it canonically resolves inside the store.
- AGP 9 applies packaging excludes (`AndroidCommon`, e.g. `**/*.txt`) to unit-test java
  resources as well. A module whose tests load such a file must remove the exclude locally
  (see `passgen/diceware/build.gradle.kts`).
- Hilt runs through KSP (no kapt; AGP 9's built-in Kotlin does not support it). Dagger compiler
  options are `ksp { arg(...) }` in `app/build.gradle.kts`.
- JGit is pinned at 4.5.7 (2019). Newer JGit needs Java 11 APIs Android only partly desugars;
  upgrading it is a large, separate job.

## Maintenance tasks

Recurring jobs with no automation. When starting a session, check the "last run" dates; if a
task is overdue, remind the maintainer before other work. After running one, update its date
here in the same commit.

### Public suffix list (monthly, and before every release)

Autofill uses Mozilla's Public Suffix List (`autofill-parser/src/main/assets/publicsuffixes`)
to tell where a domain's registrable part starts. It was a weekly bot PR upstream; here it is
manual because bot commits on the GitHub mirror would diverge from the main repository.

    ./gradlew updatePSL
    git diff --stat                     # no change: just update the date below
    ./gradlew :autofill-parser:test -PslimTests
    git commit -am "autofill-parser: update public suffix list" && git push

Last run: 2026-11-02.

## Releases

Not set up yet. Upstream's encrypted signing keystore was removed (only upstream could decrypt
it). `deploy_github_releases.yml` needs a keystore of our own, encrypted with
`scripts/encrypt-secret.sh` into `secrets/`, and the `ENCRYPT_KEY` secret. A different signing
key means the APK cannot update over upstream's install while `applicationId` is unchanged.
