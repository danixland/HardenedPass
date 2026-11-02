/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.gradle.flavors

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.HasUnitTestBuilder
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.findByType

/**
 * When the "slimTests" project property is provided, disable the unit test tasks on the `release`
 * build type to avoid running the same tests twice.
 *
 * Example: `./gradlew test -PslimTests` runs unit tests for the `debug` variant in Android App and
 * Library projects, and all tests in JVM projects.
 */
@Suppress("UnstableApiUsage")
internal fun Project.configureSlimTests() {
  if (providers.gradleProperty(SLIM_TESTS_PROPERTY).isPresent) {
    // Disable unit test tasks on the release build type for Android Library projects
    extensions.findByType<LibraryAndroidComponentsExtension>()?.run {
      beforeVariants(selector().withBuildType(BUILD_TYPE_RELEASE)) {
        (it as HasUnitTestBuilder).enableUnitTest = false
        it.androidTest.enable = false
      }
    }

    // Disable unit test tasks on the release build type for Android Application projects.
    extensions.findByType<ApplicationAndroidComponentsExtension>()?.run {
      beforeVariants(selector().withBuildType(BUILD_TYPE_RELEASE)) {
        (it as HasUnitTestBuilder).enableUnitTest = false
      }
    }
  }
}

private const val SLIM_TESTS_PROPERTY = "slimTests"
private const val BUILD_TYPE_RELEASE = "release"
