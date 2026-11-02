/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
plugins {
  id("com.github.android-password-store.git-hooks")
  id("com.github.android-password-store.kotlin-common")
  id("com.github.android-password-store.spotless")
  id("com.github.android-password-store.versions")
}

// Keep every Bouncy Castle -jdk18on module (bcprov, bcpg, bcpkix, bcutil) on one version. They
// are pulled in by PGPainless and sshj at different versions, and mixing them is unsupported.
// The legacy -jdk15on artifacts (build tooling only) stopped at 1.70 and must be left alone.
subprojects {
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      if (requested.group == "org.bouncycastle" && requested.name.endsWith("-jdk18on")) {
        useVersion(libs.versions.bouncycastle.get())
      }
    }
  }
}
