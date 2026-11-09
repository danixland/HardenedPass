# Contribution Guidelines

Thanks for taking the time to contribute! This document is a set of suggestions, not rules,
on how to contribute to HardenedPass. Ideas of any size are welcome.

## Table of contents

- [Before you start writing code](#before-you-start-writing-code)
- [Navigating the source code](#navigating-the-source-code)
- [Building the project](#building-the-project)
- [Checks before pushing](#checks-before-pushing)
- [Sending changes](#sending-changes)

## Before you start writing code

If you want to fix a bug that already has an open
[issue](https://github.com/danixland/HardenedPass/issues), comment there to say you are
working on it. If there is no issue yet, open one with the details of the bug and how you
intend to fix it, and wait for the maintainer to agree on the approach before you start. That
makes review faster and saves you from large rework.

For a new feature, open an issue first too. It might need adjustments, or it might not fit the
project's goal: a `pass` client that works flawlessly on GrapheneOS, with no Google Play
Services and no telemetry. Agreeing on the details first means you build only what will be
accepted.

Security issues are the exception: do not open a public issue, follow
[SECURITY.md](SECURITY.md) instead.

## Navigating the source code

The source code is split across 9 modules, plus the `build-logic` included build.

- `build-logic` hosts the Gradle build logic for the project.
- `autofill-parser` parses Android's Autofill structures, and deals with trust and feature
  detection for browsers.
- `coroutine-utils` helps use and test
  [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html).
- `crypto/common` defines the cryptography APIs that backends implement.
- `crypto/pgpainless` implements them with the [PGPainless](https://pgpainless.org/) library.
- `format/common` parses the `pass` file format.
- `passgen/diceware` is a password generator implementing the
  [Diceware](https://theworld.com/~reinhold/diceware.html) algorithm.
- `passgen/random` contains the default password generator.
- `ui/compose` has the theme and shared components for
  [Jetpack Compose](https://developer.android.com/jetpack/compose).
- `app` is everything else: the UI, git sync, the autofill service and the Credential Manager
  provider.

Most changes land in `app`. Some older parts are hard to follow at a glance: ask on the
[issue tracker](https://github.com/danixland/HardenedPass/issues) and they will be explained,
and improved where possible.

A [`ignore-revs-file`](https://git-scm.com/docs/git-blame#Documentation/git-blame.txt---ignore-revs-fileltfilegt)
keeps `git blame` clear of noisy changes. To use it, run
`git config blame.ignoreRevsFile .git-blame-ignore-revs` inside this repository. GitHub uses it
automatically.

## Source code conventions

- Unless you are sure of what you are doing, prefer the `unsafeLazy` function in
  `app/src/main/java/app/passwordstore/util/extensions/Extensions.kt` over Kotlin's `lazy` for
  lazily evaluated properties.
- The UI is Jetpack Compose (Material 3): screens are stateless composables driven by a
  ViewModel. Do not add XML layouts, Fragments or view binding.
- The Kotlin package stays `app.passwordstore`, while the application ID is
  `xyz.danix.hardenedpass`. Do not rename the package.
- No proprietary dependencies, no Google Play Services, no analytics or crash reporting.

## Building the project

You need JDK 17 and the Android SDK. Set `sdk.dir` in `local.properties`, or use
[Android Studio](https://developer.android.com/studio), which does it for you.

Build a debug APK with:

```shell
./gradlew :app:assembleDebug
```

You can find it in `app/build/outputs/apk/debug/`. It installs as "HardenedPass Debug", next to
the release app.

## Checks before pushing

CI runs these, with `CI=1` turning Kotlin warnings into errors. Run the same before you push:

```shell
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```

`./gradlew spotlessApply` fixes the formatting. To run most of these checks automatically on
every push, install a Git pre-push hook with `./gradlew installGitHooks` (it has no effect if
you set `core.hooksPath`).

## Sending changes

Open a pull request on [GitHub](https://github.com/danixland/HardenedPass/pulls). GitHub is a
mirror of the main repository, so the maintainer applies accepted changes there, with your
authorship kept, and closes the pull request instead of merging it.
