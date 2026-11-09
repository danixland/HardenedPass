# TODO

Open work after 0.2.0. Remove an item in the commit that finishes it.

## Bugs

- Pull fails on a store made with Create Local Repo once a remote is added in Edit Git server
  settings: "Could not read an object while parsing commit <id>". Push works, and a cloned store
  pulls fine (0.3.3, emulator, SSH). Likely cause: `PasswordRepository.addRemote` writes the refspec
  `+refs/head/*:refs/remotes/$name/*`, with `head` where git uses `heads`, so nothing is fetched.
  Stores already set up this way keep the bad refspec in `.git/config`, so a fix must repair them
  too. Not confirmed by reading the store's config.
- Key import accepts GnuPG stub and smartcard keyrings, whose secret parts live on a card: they
  pass `KeyUtils.isSecretKey` and only fail at decryption. Refuse them at import with a clear
  message (check for a secret subkey usable for decryption).

## Features and UI

- Push after autofill saves: the "Push after each change" setting skips them, because
  `AutofillSaveActivity` closes right after saving and would cancel the push.
- AppCompat out of the APK: `zxing-android-embedded` (`CaptureManager`, `zxing_CaptureTheme`) needs
  it at runtime, so the QR scanner must be replaced. That alone is not enough: `androidx.biometric`
  depends on it too, for a fingerprint dialog unused at minSdk 31, so it must be excluded or
  `BiometricPrompt` used from the platform.
- OpenPGP card support (NFC and USB): requested on the password-store mailing list (2026-11-09).
  Upstream reached cards through OpenKeychain until PGPainless replaced it in 2.0; PGPainless has
  no card support, so decryption on the card needs its own OpenPGP card (ISO 7816) layer.
