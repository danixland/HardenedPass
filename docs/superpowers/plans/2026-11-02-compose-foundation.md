# Compose UI overhaul, step 1: Foundation. Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the `compose` branch with a single-activity host (`MainActivity` + `NavHost`), the shared Compose components the later screens need, and `LaunchActivity` routing through it, while every screen still runs on the old activities.

**Architecture:** `LaunchActivity` (exported, unchanged checks) starts the new, unexported `MainActivity`. Its `NavHost` has two type-safe routes, `ListRoute` and `EntryRoute`; in this step both are temporary trampolines that start the legacy `PasswordStore` / `DecryptActivity` and finish, so the routing path is real and later steps only swap the destination bodies. Shared components live in the `ui:compose` module.

**Tech Stack:** Kotlin 2.4.21, Compose BOM 2024.09.03 (Compose 1.7), Material 3, `androidx.navigation:navigation-compose:2.8.9` (type-safe routes, the newest line that still matches Compose 1.7 and compileSdk 35), kotlinx-serialization, Hilt.

Spec: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`.

Spec deviation, decided here: the spec says the fallback scheme for Android 8 to 11 is "fixed purple". The existing `APSTheme` fallback (`Color.kt`, primary `0xFF006591`, blue) is kept instead; GrapheneOS uses dynamic color, so the fallback is rarely seen and replacing it is churn. Task 1 corrects the spec line.

**Conventions for every task**

- New files get this header:
  ```kotlin
  /*
   * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
   * SPDX-License-Identifier: GPL-3.0-only
   */
  ```
- Commits are GPG-signed (default config, never disable). End each message with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- No em dashes in docs, comments or commit messages.
- Before each commit run `./gradlew spotlessApply`, then the step's checks.

---

### Task 1: Branch and AGENTS.md exception

**Files:**
- Modify: `AGENTS.md` (section "Repository layout and remotes")
- Modify: `docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md` (Visual direction, first bullet)

- [ ] **Step 1: Create the branch**

```bash
git switch -c compose master
```

- [ ] **Step 2: Record the exception in AGENTS.md**

In "Repository layout and remotes", replace the line

```
- `master` is the only branch. Upstream's `develop` was renamed; do not recreate it.
```

with

```
- `master` is the only long-lived branch. Upstream's `develop` was renamed; do not recreate it.
- Temporary exception: the Compose UI overhaul
  (`docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md`) lives on `compose` until
  it is complete; nothing is released from it. `master` stays releasable (public suffix list,
  hotfixes). Remove this bullet when `compose` merges.
```

- [ ] **Step 3: Correct the spec's fallback color line**

In the spec, replace

```
- Material 3 "Material You": dynamic color on Android 12+, a fixed purple scheme on Android 8 to
  11 (minSdk 26). Light and dark follow the system.
```

with

```
- Material 3 "Material You": dynamic color on Android 12+, the existing fixed `APSTheme` scheme
  on Android 8 to 11 (minSdk 26). Light and dark follow the system.
```

- [ ] **Step 4: Commit and push the branch**

```bash
git add AGENTS.md docs/superpowers/specs/2026-11-02-compose-ui-overhaul-design.md
git commit -m "docs: start the compose branch for the UI overhaul

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push -u origin compose
```

Expected: push succeeds to every `origin` push URL.

---

### Task 2: Navigation and serialization dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add catalog entries**

In `[libraries]`, after `androidx-lifecycle-viewmodelKtx`, add:

```toml
androidx-navigation-compose = "androidx.navigation:navigation-compose:2.8.9"
```

After `kotlinx-coroutines-test`, add:

```toml
kotlinx-serialization-core = "org.jetbrains.kotlinx:kotlinx-serialization-core:1.12.0"
```

In `[plugins]`, after `kotlin-composeCompiler`, add:

```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Apply them in the app module**

In `app/build.gradle.kts`, add to `plugins { }` after `alias(libs.plugins.kotlin.composeCompiler)`:

```kotlin
  alias(libs.plugins.kotlin.serialization)
```

In `dependencies { }`, after `implementation(libs.androidx.material)`:

```kotlin
  implementation(libs.androidx.navigation.compose)
```

and after `implementation(libs.kotlinx.coroutines.core)`:

```kotlin
  implementation(libs.kotlinx.serialization.core)
```

- [ ] **Step 3: Verify it builds**

Run: `CI=1 ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If the serialization plugin fails to resolve because the Kotlin
plugin is already on the build-logic classpath, replace the `alias(...)` with
`id("org.jetbrains.kotlin.plugin.serialization")` and add
`implementation("org.jetbrains.kotlin:kotlin-serialization:${libs.versions.kotlin.get()}")`
to `build-logic/build.gradle.kts` dependencies (mirroring how `build-kotlin` is declared there);
re-run.

- [ ] **Step 4: Check the dependency report did not move baselined jars**

Run: `CI=1 ./gradlew :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. If lint reports baseline entries no longer matching (see AGENTS.md:
`app/lint-baseline.xml` is keyed on jar cache paths), update the moved paths in
`app/lint-baseline.xml` and re-run.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/lint-baseline.xml build-logic
git commit -m "build(app): add navigation-compose and kotlinx-serialization

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Breadcrumb model (TDD)

The list screen shows the current folder as chips: root, then each folder level. The model is a
pure function of the folder's path relative to the store root.

**Files:**
- Modify: `ui/compose/build.gradle.kts`
- Create: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/Breadcrumbs.kt`
- Test: `ui/compose/src/test/kotlin/app/passwordstore/ui/compose/BreadcrumbsTest.kt`

- [ ] **Step 1: Give ui:compose unit tests**

In `ui/compose/build.gradle.kts`, add to `dependencies { }`:

```kotlin
  testImplementation(libs.bundles.testDependencies)
```

- [ ] **Step 2: Write the failing test**

`ui/compose/src/test/kotlin/app/passwordstore/ui/compose/BreadcrumbsTest.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class BreadcrumbsTest {

  @Test
  fun rootOnly() {
    assertEquals(listOf(Crumb("", "")), crumbsFor(""))
  }

  @Test
  fun nestedPath() {
    assertEquals(
      listOf(Crumb("", ""), Crumb("work", "work"), Crumb("servers", "work/servers")),
      crumbsFor("work/servers"),
    )
  }

  @Test
  fun ignoresStraySlashes() {
    assertEquals(
      listOf(Crumb("", ""), Crumb("work", "work"), Crumb("servers", "work/servers")),
      crumbsFor("/work//servers/"),
    )
  }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./gradlew :ui:compose:testDebugUnitTest`
Expected: compilation FAILS with `Unresolved reference 'Crumb'` / `'crumbsFor'`.

- [ ] **Step 4: Implement the model**

`ui/compose/src/main/kotlin/app/passwordstore/ui/compose/Breadcrumbs.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

/** One breadcrumb chip. The root crumb has an empty [label] and [path]. */
public data class Crumb(val label: String, val path: String)

/** Crumbs for a folder at [relativePath] inside the store: root first, then each level. */
public fun crumbsFor(relativePath: String): List<Crumb> {
  val parts = relativePath.split('/').filter { it.isNotEmpty() }
  return listOf(Crumb("", "")) +
    parts.indices.map { i -> Crumb(parts[i], parts.subList(0, i + 1).joinToString("/")) }
}
```

- [ ] **Step 5: Run it to verify it passes**

Run: `./gradlew :ui:compose:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 6: Commit**

```bash
git add ui/compose/build.gradle.kts ui/compose/src/main/kotlin/app/passwordstore/ui/compose/Breadcrumbs.kt ui/compose/src/test
git commit -m "feat(ui-compose): add the breadcrumb model

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Shared components

Four building blocks used by the list, entry view and editor (see the spec's mockup
descriptions). Content descriptions are parameters, because `ui:compose` has no string
resources and the app owns all translations.

**Files:**
- Modify: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/Breadcrumbs.kt`
- Create: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/GroupedSurface.kt`
- Create: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/EntryRows.kt`
- Create: `ui/compose/src/main/kotlin/app/passwordstore/ui/compose/FieldRow.kt`
- Create: `ui/compose/src/main/res/drawable/ic_folder_24.xml`

- [ ] **Step 1: Folder icon**

`ui/compose/src/main/res/drawable/ic_folder_24.xml` (Material "folder" glyph):

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
  android:width="24dp"
  android:height="24dp"
  android:viewportWidth="24"
  android:viewportHeight="24">
  <path
    android:fillColor="#FF000000"
    android:pathData="M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z" />
</vector>
```

- [ ] **Step 2: BreadcrumbBar**

Append to `Breadcrumbs.kt` (add these imports at the top of the file, after the package line):

```kotlin
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.passwordstore.ui.compose.preview.ThemePreviews
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingSmall
```

```kotlin
/**
 * Folder location as chips. The last crumb is highlighted; tapping any crumb reports its path.
 * [rootDescription] is the content description of the root (home) chip.
 */
@Composable
public fun BreadcrumbBar(
  crumbs: List<Crumb>,
  rootDescription: String,
  onCrumbClick: (path: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SpacingLarge),
    horizontalArrangement = Arrangement.spacedBy(SpacingSmall),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    crumbs.forEachIndexed { i, crumb ->
      if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
      val current = i == crumbs.lastIndex
      AssistChip(
        onClick = { onCrumbClick(crumb.path) },
        label = {
          if (crumb.path.isEmpty()) Icon(Icons.Filled.Home, contentDescription = rootDescription)
          else Text(crumb.label)
        },
        colors =
          if (current)
            AssistChipDefaults.assistChipColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          else AssistChipDefaults.assistChipColors(),
        border = null,
      )
    }
  }
}

@ThemePreviews
@Composable
private fun BreadcrumbBarPreview() {
  APSTheme { BreadcrumbBar(crumbsFor("work/servers"), "Store root", onCrumbClick = {}) }
}
```

- [ ] **Step 3: GroupedSurface**

`GroupedSurface.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Rounded container that groups related rows (folders, entries, fields). */
@Composable
public fun GroupedSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
  Surface(
    modifier = modifier.padding(horizontal = SpacingMedium, vertical = SpacingMedium / 2),
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceContainerLow,
  ) {
    Column(content = content)
  }
}
```

- [ ] **Step 4: Folder and entry rows**

`EntryRows.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.passwordstore.ui.compose.preview.ThemePreviews
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Circle with the first letter of [name]; the color pair is picked from the name's hash. */
@Composable
public fun LetterAvatar(name: String, modifier: Modifier = Modifier) {
  val scheme = MaterialTheme.colorScheme
  val pairs =
    listOf(
      scheme.primaryContainer to scheme.onPrimaryContainer,
      scheme.secondaryContainer to scheme.onSecondaryContainer,
      scheme.tertiaryContainer to scheme.onTertiaryContainer,
    )
  val (bg, fg) = pairs[Math.floorMod(name.hashCode(), pairs.size)]
  Box(modifier.size(36.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
    Text(name.take(1).uppercase(), color = fg, style = MaterialTheme.typography.titleMedium)
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowShell(
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  selected: Boolean,
  modifier: Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(horizontal = SpacingLarge, vertical = SpacingMedium + 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    content()
  }
}

/** A folder row: icon, name, entry count. */
@Composable
public fun FolderRow(
  name: String,
  count: Int,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onLongClick: () -> Unit = {},
) {
  RowShell(onClick, onLongClick, selected, modifier) {
    Box(
      Modifier.size(36.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painterResource(R.drawable.ic_folder_24),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
    Text(name, Modifier.weight(1f).padding(start = SpacingLarge))
    Text(
      count.toString(),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** An entry row: letter avatar, name, optional subtitle (username, "OTP"). */
@Composable
public fun EntryRow(
  name: String,
  subtitle: String?,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  selected: Boolean = false,
  onLongClick: () -> Unit = {},
) {
  RowShell(onClick, onLongClick, selected, modifier) {
    LetterAvatar(name)
    Column(Modifier.weight(1f).padding(start = SpacingLarge)) {
      Text(name)
      if (subtitle != null)
        Text(
          subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
  }
}

@ThemePreviews
@Composable
private fun RowsPreview() {
  APSTheme {
    GroupedSurface {
      FolderRow("legacy", 2, onClick = {})
      EntryRow("git.example.org", "you", onClick = {})
      EntryRow("vpn", "you · OTP", onClick = {}, selected = true)
    }
  }
}
```

- [ ] **Step 5: Field row**

`FieldRow.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.passwordstore.ui.compose.preview.ThemePreviews
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Content descriptions for [FieldRow]'s buttons, supplied by the app's translated strings. */
public data class FieldRowLabels(val copy: String, val show: String, val hide: String)

/**
 * A labelled value with an optional copy button. When [masked] is true the value is hidden
 * behind dots until the reveal toggle is pressed.
 */
@Composable
public fun FieldRow(
  label: String,
  value: String,
  labels: FieldRowLabels,
  modifier: Modifier = Modifier,
  masked: Boolean = false,
  onCopy: (() -> Unit)? = null,
) {
  var revealed by rememberSaveable { mutableStateOf(false) }
  Row(
    modifier.fillMaxWidth().padding(start = SpacingLarge, end = SpacingMedium, top = SpacingMedium, bottom = SpacingMedium),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(if (masked && !revealed) "•".repeat(10) else value, style = MaterialTheme.typography.bodyLarge)
    }
    if (masked)
      IconButton(onClick = { revealed = !revealed }) {
        Icon(
          painterResource(if (revealed) R.drawable.baseline_visibility_off_24 else R.drawable.baseline_visibility_24),
          contentDescription = if (revealed) labels.hide else labels.show,
        )
      }
    if (onCopy != null)
      IconButton(onClick = onCopy) {
        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = labels.copy)
      }
  }
}

@ThemePreviews
@Composable
private fun FieldRowPreview() {
  val labels = FieldRowLabels("Copy", "Show", "Hide")
  APSTheme {
    GroupedSurface {
      FieldRow("Password", "hunter2", labels, masked = true, onCopy = {})
      FieldRow("Username", "you", labels, onCopy = {})
    }
  }
}
```

The masked placeholder is a fixed 10 dots so the length of the secret is not shown.

- [ ] **Step 6: Build, format and lint the module**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :ui:compose:assembleRelease :ui:compose:lint :ui:compose:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add ui/compose/src
git commit -m "feat(ui-compose): add breadcrumb bar, grouped surface, entry and field rows

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: MainActivity with type-safe routes

**Files:**
- Create: `app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add the activity next to `LaunchActivity`)

- [ ] **Step 1: Write MainActivity**

`app/src/main/java/app/passwordstore/ui/main/MainActivity.kt`:

```kotlin
/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.fragment.app.FragmentActivity
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.crypto.BasePGPActivity
import app.passwordstore.ui.crypto.DecryptActivity
import app.passwordstore.ui.passwords.PasswordStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.serialization.Serializable

/** The password list, at the store root. */
@Serializable data object ListRoute

/** One entry. [filePath] was checked with [LaunchActivity.isFileInRepo] before routing here. */
@Serializable data class EntryRoute(val filePath: String)

/**
 * Single-activity host for the Compose UI. Not exported: it is only started by
 * [LaunchActivity], after its biometric gate and path check. Extends [FragmentActivity] only
 * because BiometricPrompt needs one; it hosts no fragments.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val filePath = intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)
    val start: Any = if (filePath != null) EntryRoute(filePath) else ListRoute
    setContent {
      APSTheme {
        NavHost(rememberNavController(), startDestination = start) {
          composable<ListRoute> {
            LegacyScreen(Intent(this@MainActivity, PasswordStore::class.java).setAction(Intent.ACTION_VIEW))
          }
          composable<EntryRoute> { entry ->
            val route = entry.toRoute<EntryRoute>()
            LegacyScreen(
              Intent(this@MainActivity, DecryptActivity::class.java)
                .putExtra(BasePGPActivity.EXTRA_FILE_PATH, route.filePath)
                .putExtra(
                  BasePGPActivity.EXTRA_REPO_PATH,
                  PasswordRepository.getRepositoryDirectory().absolutePath,
                )
            )
          }
        }
      }
    }
  }

  // ponytail: trampoline to a screen not yet ported; each port replaces one call with the
  // real composable, and the last port deletes this function.
  @Composable
  private fun LegacyScreen(intent: Intent) {
    LaunchedEffect(Unit) {
      startActivity(intent)
      finish()
    }
  }
}
```

- [ ] **Step 2: Register it in the manifest**

In `app/src/main/AndroidManifest.xml`, directly after the closing `</activity>` of
`.ui.main.LaunchActivity`, add:

```xml
    <activity
      android:name=".ui.main.MainActivity"
      android:exported="false" />
```

- [ ] **Step 3: Build**

Run: `./gradlew spotlessApply && CI=1 ./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. If `startDestination = start` with `start: Any` does not compile,
use `startDestination = if (filePath != null) EntryRoute(filePath) else ListRoute` inline; the
`NavHost` overload taking `Any` exists from navigation 2.8.0.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/main/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(app): add the single-activity Compose host

Its two routes hand off to the legacy list and decrypt activities until
those screens are ported.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Route LaunchActivity through MainActivity

**Files:**
- Modify: `app/src/main/java/app/passwordstore/ui/main/LaunchActivity.kt` (`startTargetActivity`, `getDecryptIntent`, imports)

- [ ] **Step 1: Replace the target selection**

Replace `getDecryptIntent()` and the body of `startTargetActivity` up to and including
`startActivity(intentToStart)` with:

```kotlin
  private fun startTargetActivity(noAuth: Boolean) {
    // This activity is exported, so the file path is untrusted input: only forward it when it
    // points at a file inside the password store. MainActivity is not exported and trusts it.
    val repoDir = PasswordRepository.getRepositoryDirectory()
    val filePath = intent.getStringExtra(BasePGPActivity.EXTRA_FILE_PATH)
    val intentToStart = Intent(this, MainActivity::class.java)
    if (intent.action == ACTION_DECRYPT_PASS && isFileInRepo(filePath, repoDir))
      intentToStart.putExtra(BasePGPActivity.EXTRA_FILE_PATH, filePath)
    startActivity(intentToStart)
```

(The `Handler(...).postDelayed({ finish() }, ...)` line and the rest stay.) Delete the now
unused imports `app.passwordstore.ui.crypto.DecryptActivity` and
`app.passwordstore.ui.passwords.PasswordStore`.

- [ ] **Step 2: Run the existing path-check test**

Run: `./gradlew :app:testDebugUnitTest --tests app.passwordstore.ui.main.LaunchActivityTest`
Expected: PASS (the `isFileInRepo` boundary is untouched).

- [ ] **Step 3: Full local check**

Run:
```bash
./gradlew spotlessApply
CI=1 ./gradlew spotlessCheck test -PslimTests :app:assembleDebug :app:assembleRelease \
  lint metalavaCheckCompatibilityRelease
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/passwordstore/ui/main/LaunchActivity.kt
git commit -m "feat(app): route the launcher through MainActivity

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Emulator check and push

No secrets or real store: use the e2e environment from AGENTS.md.

- [ ] **Step 1: Start the e2e server and emulator, install**

```bash
scripts/e2e-env.sh up
$ANDROID_HOME/emulator/emulator -avd aps-test -no-window -no-audio -no-boot-anim \
  -no-snapshot-save -gpu swiftshader_indirect &
adb wait-for-device
for s in animator_duration_scale transition_animation_scale window_animation_scale; do
  adb shell settings put global $s 0; done
adb shell settings put secure autofill_service null
./gradlew installDebug
```

(`ANDROID_HOME` is the `sdk.dir` from `local.properties`.)

- [ ] **Step 2: Launcher path**

```bash
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity
sleep 3; adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity"
```

Expected: the resumed activity is `app.passwordstore.ui.passwords.PasswordStore` (or
`OnboardingActivity` on a fresh install, which `PasswordStore` redirects to); `MainActivity` is
not in the task (it finished). If the store is not set up, go through onboarding: clone
`ssh://git@10.0.2.2:2222/srv/git/store.git` and import the test key (`scripts/e2e-env.sh push-keys`).

- [ ] **Step 3: Entry path (shortcut route)**

With the store cloned, open an entry once from the list so a dynamic shortcut exists, then:

```bash
adb shell am start -n xyz.danix.hardenedpass.debug/app.passwordstore.ui.main.LaunchActivity \
  -a DECRYPT_PASS --es FILE_PATH "<absolute path of that .gpg file, from the shortcut>"
sleep 3; adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity"
```

Expected: `DecryptActivity` resumed, entry decrypts (read via `uiautomator dump`, the screen is
`FLAG_SECURE`). Repeat with `--es FILE_PATH /etc/hosts`: expected `PasswordStore`, not
`DecryptActivity`.

- [ ] **Step 4: Tear down and push**

```bash
adb emu kill
scripts/e2e-env.sh down
git push
```

---

## Self-review notes

- Spec coverage for step 1: dependencies (Task 2; `hilt-navigation-compose` is deferred to the
  step 2 plan, where the first `hiltViewModel()` appears), `MainActivity` + `NavHost` (Task 5),
  theme (existing `APSTheme`, fallback deviation recorded), shared components (Tasks 3 and 4),
  `LaunchActivity` routing (Task 6), branch and AGENTS.md exception (Task 1), runnable branch
  via trampolines (Tasks 5 and 7).
- Out of scope for this plan, each in its own later plan: list, entry view, editor, setup,
  settings/log/proxy, autofill UI, cleanup.
