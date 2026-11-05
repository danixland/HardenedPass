# TODO

Open work after 0.2.0. Remove an item in the commit that finishes it.

## Features and UI

- Push after save: saves, edits, moves and deletes commit locally but do not push, so a change can
  sit unpushed and collide with a later desktop change (a modify/delete conflict leaves the store
  in a stopped rebase). Push after each commit, or at least warn about unpushed commits.
- AppCompat out of the APK: `zxing-android-embedded` (`CaptureManager`, `zxing_CaptureTheme`) needs
  it at runtime, so the QR scanner must be replaced. That alone is not enough: `androidx.biometric`
  depends on it too, for a fingerprint dialog unused at minSdk 31, so it must be excluded or
  `BiometricPrompt` used from the platform.
