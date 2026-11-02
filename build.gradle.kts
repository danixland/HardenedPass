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

// Keep every Bouncy Castle module (bcprov, bcpg, bcpkix, bcutil) on one version. They are
// pulled in by PGPainless and sshj at different versions, and mixing them is unsupported.
subprojects {
  configurations.configureEach {
    resolutionStrategy.eachDependency {
      if (requested.group == "org.bouncycastle") useVersion(libs.versions.bouncycastle.get())
    }
  }
}
