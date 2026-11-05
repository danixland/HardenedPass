# AGENTS.md

Guidance for AI coding agents (Claude Code, opencode, etc) working in this repository.

## What this is

HardenedPass: a maintained fork of Android Password Store, an Android client for `pass`
(OpenPGP-encrypted files in a git repository). Upstream
(`android-password-store/Android-Password-Store`) was archived in October 2024; the fork
started in November 2026 from upstream's last commit. Kotlin, Gradle, about 18k lines. The
goal is to work flawlessly on GrapheneOS: no Google Play Services, no telemetry.

Modules: `app` (UI, git sync, autofill service), `autofill-parser`, `crypto:common`,
`crypto:pgpainless`, `format:common`, `passgen:diceware`, `passgen:random`, `ui:compose`,
`coroutine-utils`. Build conventions live in `build-logic/`.

The UI is Jetpack Compose with Material 3. `MainActivity` hosts every screen in one `NavHost`
(list, entry, editor, setup, settings); `LaunchActivity` is the only exported activity. The
autofill service starts its four activities (`AutofillFilterView`, `AutofillDecryptActivity`,
`AutofillSaveActivity`, `AutofillPublisherChangedActivity`) directly, so they stay separate and
draw with the same theme.

`applicationId` is `xyz.danix.hardenedpass` (debug: `.debug` suffix, label "HardenedPass
Debug"), so it installs next to upstream's `app.passwordstore` and never updates over it. The
Kotlin package stays `app.passwordstore`; renaming it is churn with no user-visible effect.
Keep "GrapheneOS" out of the app name: it is that project's trademark.

minSdk is 31: GrapheneOS runs current Android only, and Android 12 has
`UiModeManager.setApplicationNightMode`.

## Repository layout and remotes

- `master` is the only branch. Upstream's `develop` was renamed; do not recreate it.
- `origin` fetches from the maintainer's own git server (the main repository) and pushes to it
  and to the GitHub fork (`danixland/HardenedPass`) in one `git push`.
  GitHub is a push mirror: never create commits on GitHub (bots, web edits), they would
  diverge from the main repository.
- `upstream` is fetch-only (push URL disabled).
- Work directly on `master`, no PRs.

## Build and test

Needs JDK 17 and the Android SDK. `local.properties` (gitignored) holds `sdk.dir`.

    ./gradlew :app:assembleDebug                # debug APK
    ./gradlew test -PslimTests                  # unit tests, as CI runs them
    ./gradlew spotlessApply                     # formatting (ktfmt); CI runs spotlessCheck
    ./gradlew lint                              # all modules and variants, as CI runs it

Prefix with `CI=1` to match CI: it turns Kotlin warnings into errors, so a deprecation warning
that passes locally fails CI. Before committing, run:

    CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
      lint metalavaCheckCompatibilityRelease

No product flavors: the app has no proprietary dependencies, no Sentry and no Play Services
(the upstream `nonFree` flavor was removed). Scope `assemble*` to `:app`, which is all CI and
releases need; a bare `assembleRelease` also builds every library module.

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
    ./gradlew :app:installDebug

If a physical device is connected too, `export ANDROID_SERIAL=emulator-5554` first, so every
`adb` and Gradle install command targets the emulator. Agent shells keep no functions or
variables between calls: keep that export and any helpers in a sourced file, and prefix helper
names (`e2e_pass`, not `pass`, which is the real `pass` and reaches a real store). Drive it with
`adb shell input`, `uiautomator dump` and `screencap`; Compose nodes carry `text` and
`content-desc` in the dump, no resource ids, so match on those. FABs ("Import PGP key", "New
entry") carry only `content-desc`, and the list's search bar is itself an `EditText`. The entry
view, the editor and its generator sheet, the proxy screen, the passphrase dialogs, the git
credential dialog and the autofill decryption and save windows set `FLAG_SECURE`, so screenshots of them are black; read
them from the `uiautomator` dump. After boot, wait until the keyguard is dismissed and user
storage is unlocked before `adb uninstall` or `push-keys`: earlier, uninstall fails with
`DELETE_FAILED_INTERNAL_ERROR` and `/sdcard/Download` does not exist yet. In a Compose text
field, wait about a second after the tap before clearing it (Ctrl+A, DEL), or the clear misses.
`adb shell run-as <pkg> sh -c '...'` needs the whole command quoted as one `adb` argument
(`adb shell "run-as <pkg> sh -c '...'"`), or the device shell splits it.
The `aps-test` AVD has lock PIN 1234: until it is entered at the keyguard, user storage stays
locked. On the host, `ssh` to the e2e server needs `-F none -o IdentityAgent=none`, or
`AddKeysToAgent` makes gpg-agent ask for a passphrase for the throwaway key. Fingerprint: `adb emu finger touch 1` after enrolling one in Settings (needs a lock PIN:
`adb shell locksettings set-pin 1234` on the emulator only). Disable animations
(`settings put global animator_duration_scale 0`, same for `transition_` and `window_`)
or `uiautomator dump` waits for an idle state that never comes. The OTP screen never goes idle
anyway (live countdown), so its code can only be checked by eye. A windowed emulator needs the
host's `libbsd`. The `aps-test` AVD also has upstream's debug app as its autofill
service, which grabs passphrase fields: `settings put secure autofill_service null` first. Debug
builds from the main checkout are signed with the local release keystore, builds from a clean
worktree with `~/.android/debug.keystore`, so an in-place upgrade between them needs `apksigner`
re-signing.

Autofill cannot be tested on the emulator image: Settings is denylisted in
`OreoAutofillService`, and the only browser (WebView Shell) is not a trusted browser. Its
activities are not exported; after `adb root` (the AOSP image allows it), `am start -n` opens the
picker, the publisher warning and the save editor with test extras, but not a real decryption
(it needs a client state `Bundle`). Use a real device with a trusted browser (e.g. Firefox)
instead:

- `adb reverse tcp:2222 tcp:2222` lets the device clone `ssh://git@127.0.0.1:2222/...` from the
  e2e server over USB; serve a local login page the same way (`python3 -m http.server` on
  127.0.0.1, `adb reverse` its port). Nothing is exposed on the network.
- Record `settings get secure autofill_service` and `global stay_on_while_plugged_in` first,
  restore both afterwards, then `adb uninstall xyz.danix.hardenedpass.debug`, delete the pushed
  keys from Download and `adb reverse --remove-all`.
- Set `svc power stayon usb` before driving: if the screen locks, taps land on the lock
  screen's PIN pad. Check `dumpsys window | grep mCurrentFocus` (and `isKeyguardShowing`)
  before tapping. Never type the owner's PIN.
- On a device with Gboard, fill suggestions are inline chips in the keyboard bar, localized
  (Italian: "Cerca la voce" is the app's "Search entry" chip).
- Only read screens of the debug app, the test page and the autofill UI; the device holds the
  owner's real data.
- On the Pixel 6 Pro (GrapheneOS, Android 17) `uiautomator dump` fails on the debug app and on
  Firefox with `null root node`, so drive by `screencap` and tap coordinates; secure windows need
  the owner. The system file picker can show real files: the owner picks the keys there.

Last full emulator run (2026-11-05, Android 15, minSdk 31 build): clone over SSH with a
passphrase key (wrong, cancel, remembered, kept through a recreation), missing SSH key dialog, git
error dialog, snackbars, conflict recovery (abort rebase, push a new branch), overwrite confirmation
on move, light, dark and system themes with bar colors, QR scanner opens, decrypt, TOTP and
fingerprint unlock passed. Last Firefox autofill run (2026-11-05): Pixel 6 Pro on Android 17 with the
signed minSdk 31 release build (fill, save, sync, abort rebase, hard reset), all passed. The tablet
(fill, save, stale cached passphrase) was last run before that build.

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
- `LaunchActivity` is exported and forwards `FILE_PATH` to `MainActivity` (not exported) only
  after `isFileInRepo` checks it canonically resolves inside the store; `MainActivity` opens it as
  `EntryRoute`, whose destination checks it again before decrypting.
- Window themes derive from the platform `android:Theme.Material.*.NoActionBar`, activities from
  `FragmentActivity` (`BiometricPrompt` needs it). Git operations and store actions show dialogs and
  snackbars through the activity-scoped `Prompts` ViewModel (`activity.prompts`), drawn by
  `PromptHost`, which only `MainActivity` and `AutofillSaveActivity` host. AppCompat stays in the
  APK through zxing (`CaptureManager`, `zxing_CaptureTheme`) and `androidx.biometric`;
  `oreo_autofill_dataset.xml` stays a layout: fill suggestions are `RemoteViews` drawn by the
  system.
- sshj gets Ed25519 keys from Bouncy Castle: `SshjConfig.kt` pins `KeyFactory.Ed25519` to it at
  provider position 1. Conscrypt from Android 14 mainline updates serves that factory with keys
  sshj maps to `KeyType.UNKNOWN` ("Don't know how to encode key"), breaking every Ed25519 host
  and user key. The emulator image does not show it; a 2026-03 Android 14 tablet does.
- AGP 9 applies packaging excludes (`AndroidCommon`, e.g. `**/*.txt`) to unit-test java
  resources as well. A module whose tests load such a file must remove the exclude locally
  (see `passgen/diceware/build.gradle.kts`).
- Hilt runs through KSP (no kapt; AGP 9's built-in Kotlin does not support it). Dagger compiler
  options are `ksp { arg(...) }` in `app/build.gradle.kts`.
- Secrets (Git and proxy credentials, the PGP passphrase cache, wrapped Ed25519 SSH keys) are
  AES-GCM encrypted under Android Keystore keys by `util/crypto/KeystorePreferences.kt`.
  androidx security-crypto was removed; `removeSecurityCryptoData` in `Migrations.kt` deleted
  its data once on upgrade from 0.1.x.
- Autofill save on Android 14+: the framework sends the save `IntentSender` from the app being
  filled, which is not opted in to background activity starts, so `AutofillSaveActivity` opens
  hidden behind it (`Background activity launch blocked` in logcat). `FixedSaveCallback` starts it
  from the service instead, which the system has bound. Firefox's own save prompt shows as well.
- JGit is on 5.13.x, the last line that needs only Java 8 APIs. JGit 6+ needs Java 11 APIs
  Android only partly desugars; moving to it is a large, separate job. Window cache JMX is off
  in `Application` and GC runs without JGit's PID lock, because Android has no JMX.

- The about screen's `LICENSES` list (`AboutScreen.kt`) is kept by hand: update it when a
  dependency that ships in the APK comes or goes. Its Ko-fi logo must stay unchanged, per Ko-fi's
  guidance, on the white button that keeps its dark outline visible.

## Open work

`TODO.md` lists open features and fixes. Remove an item in the commit that finishes it.

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

Last run: 2026-11-05.

## Releases

Signed with the maintainer's own key (certificate `CN=HardenedPass`, SHA-256
`72:50:16:66:9E:48:2F:9A:C3:71:DE:E8:DF:19:A2:A1:B3:4F:A7:6D:1A:F0:5B:2A:23:78:47:25:B1:B4:9C:83`).
The master copy lives outside the repo, never in it. Losing it means installed copies can never
be updated again.

- Local: `keystore.jks` and `keystore.properties` at the repo root (gitignored, `/keystore.*`)
  make `./gradlew :app:assembleRelease` produce a signed `app-release.apk`.
- CI: `secrets/keystore.cipher` and `secrets/props.cipher` are those two files encrypted with
  `openssl enc -aes-256-cbc -md sha256 -pbkdf2`; the key is the `ENCRYPT_KEY` repository
  secret. `scripts/signing-setup.sh` decrypts them, `signing-cleanup.sh` deletes them.
- Pushing a `vX.Y.Z` tag runs `deploy_github_releases.yml`: it builds the signed APK and creates
  a draft GitHub Release with `HardenedPass_vX.Y.Z.apk` and its `.sha256`. The notes are that
  version's `CHANGELOG.md` section in full, so the release fails without one.
- Never print, log or commit the keystore password or `ENCRYPT_KEY`.
