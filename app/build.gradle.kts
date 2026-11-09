/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
@file:Suppress("UnstableApiUsage")

plugins {
  id("com.github.android-password-store.android-application")
  id("com.github.android-password-store.kotlin-android")
  id("com.github.android-password-store.versioning-plugin")
  id("com.github.android-password-store.rename-artifacts")
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
  alias(libs.plugins.kotlin.composeCompiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  compileOptions { isCoreLibraryDesugaringEnabled = true }
  namespace = "app.passwordstore"

  defaultConfig {
    applicationId = "xyz.danix.hardenedpass"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildFeatures { compose = true }

  androidResources { generateLocaleConfig = true }

  packaging {
    resources.excludes.add("META-INF/versions/**")
    // Every Bouncy Castle jar ships the same license file; keep one copy in the APK.
    resources.pickFirsts.add("META-INF/LICENSE.md")
  }
}

ksp {
  // https://dagger.dev/dev-guide/compiler-options
  arg("dagger.fastInit", "enabled")
  arg("dagger.experimentalDaggerErrorMessages", "enabled")
  arg("dagger.hilt.shareTestComponents", "true")
  arg("dagger.moduleBindingValidation", "ERROR")
}

dependencies {
  implementation(platform(libs.compose.bom))
  ksp(libs.dagger.hilt.compiler)
  implementation(libs.androidx.annotation)
  coreLibraryDesugaring(libs.android.desugarJdkLibs)
  implementation(projects.autofillParser)
  implementation(projects.coroutineUtils)
  implementation(projects.crypto.pgpainless)
  implementation(projects.format.common)
  implementation(projects.passgen.diceware)
  implementation(projects.passgen.random)
  implementation(projects.ui.compose)
  implementation(libs.androidx.activity)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.autofill)
  implementation(libs.androidx.biometricKtx)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.documentfile)
  // FragmentActivity, which BiometricPrompt needs.
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.hilt.navigation.compose)
  implementation(libs.bundles.androidxLifecycle)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.navigation.compose)
  debugImplementation(libs.compose.ui.tooling)
  implementation(libs.dagger.hilt.android)

  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.core)

  implementation(libs.aps.sublimeFuzzy)
  implementation(libs.aps.zxingAndroidEmbedded)

  implementation(libs.thirdparty.jgit) {
    exclude(group = "org.apache.httpcomponents", module = "httpclient")
  }
  implementation(libs.thirdparty.kotlinResult)
  implementation(libs.thirdparty.logcat)
  implementation(libs.thirdparty.leakcanary.plumber)
  implementation(libs.thirdparty.sshj)
  implementation(libs.thirdparty.bouncycastle.bcprov)
  implementation(libs.thirdparty.bouncycastle.bcutil)

  implementation(libs.thirdparty.slf4j.api) {
    because("SSHJ now uses SLF4J 2.0 which we don't want")
  }

  testImplementation(libs.testing.robolectric)
  testImplementation(libs.testing.sharedPrefsMock)
  testImplementation(libs.thirdparty.pgpainless)
  testImplementation(libs.bundles.testDependencies)
}
