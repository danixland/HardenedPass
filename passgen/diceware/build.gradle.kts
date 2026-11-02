/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
plugins {
  id("com.github.android-password-store.android-library")
  id("com.github.android-password-store.kotlin-android")
}

android {
  namespace = "app.passwordstore.passgen.diceware"
  androidResources.enable = true
  sourceSets { getByName("test") { resources.directories.add("src/main/res/raw") } }
  // AGP 9 applies packaging excludes to unit test resources too; keep the word list.
  packaging.resources.excludes.remove("**/*.txt")
}

dependencies {
  implementation(libs.dagger.hilt.core)
  testImplementation(libs.bundles.testDependencies)
}
