# TODO

Open work after 0.2.0. Remove an item in the commit that finishes it.

## Features and UI

- QR scanner: `zxing-android-embedded` is the last thing keeping AppCompat in the APK
  (`androidx.biometric` references it only on the fingerprint dialog path, unused at minSdk 31).
  Replacing it would let AppCompat go.
