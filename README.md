# Password Store

An Android client for [pass](https://www.passwordstore.org/), the standard Unix password manager.
Passwords are stored as OpenPGP-encrypted files in a git repository, so the same store works with
`pass` on the desktop and with this app on the phone.

This is a maintained fork of
[android-password-store/Android-Password-Store](https://github.com/android-password-store/Android-Password-Store),
which was archived in October 2024. All credit for the original work goes to the
Android Password Store authors.

## Changes from upstream

- Decrypts messages GnuPG 2.3+ writes by default (OCB / LibrePGP AEAD), which upstream rejected
  as "non-standard AEAD".
- Rejects keys without an encryption-capable subkey on import.
- Bouncy Castle 1.86 and PGPainless 1.7.8, fixing known Bouncy Castle advisories.
- Decrypt screen no longer reachable by other apps with arbitrary file paths.
- No build scans uploaded to third-party services.

## Building

Requires JDK 17 and the Android SDK command-line tools. Point Gradle at the SDK with a
`local.properties` file (`sdk.dir=/path/to/Android/Sdk`), then:

```
./gradlew assembleFreeDebug      # APK in app/build/outputs/apk/free/debug/
./gradlew test -PslimTests       # unit tests
```

The `free` flavor has no proprietary dependencies. The `nonFree` flavor adds Sentry crash
reporting and Google Play Services SMS OTP autofill.

## Issues

Report bugs and feature requests on the
[issue tracker](https://github.com/danixland/Android-Password-Store/issues).

## License

GPL-3.0-only, see [LICENSE.txt](LICENSE.txt).

Copyright © 2014-2024 The Android Password Store Authors.
Modifications Copyright (C) 2026 Danilo M. <danix@danix.xyz>.

## Development Approach

This project is developed using AI-assisted tools. Code is generated with the help of AI based on human-provided specifications, design decisions, and iterative feedback.

All contributions are reviewed, tested, and curated by the maintainer before being included in the codebase. AI is used as a productivity and exploration tool, while human oversight remains central to all decisions.

The goal is to combine the flexibility of AI-assisted development with standard open-source practices such as transparency, review, and accountability.
