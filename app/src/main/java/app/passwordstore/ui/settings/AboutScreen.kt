/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.crypto.copyTextToClipboard
import app.passwordstore.util.extensions.getPackageInfoCompat
import app.passwordstore.util.extensions.snackbar
import java.security.MessageDigest

private const val REPOSITORY = "https://github.com/danixland/HardenedPass"
private const val DOCUMENTATION = "https://danix.xyz/hardenedpass"
private const val KO_FI = "https://ko-fi.com/K6Y520C9I6"

/**
 * What ships in the APK and its license, by hand: update it when a dependency comes or goes. A
 * generating plugin would be one more build dependency for a list this short.
 */
private val LICENSES =
  listOf(
    "AndroidX, Jetpack Compose" to "Apache-2.0",
    "Apache Commons Codec, Curtains, Guava, JavaEWAH, JSpecify, uri-kmp" to "Apache-2.0",
    "Bouncy Castle" to "Bouncy Castle Licence (MIT)",
    "Dagger, Hilt" to "Apache-2.0",
    "EFF Diceware word list" to "CC-BY",
    "JGit" to "EDL-1.0 (BSD-3-Clause)",
    "kotlin-result" to "ISC",
    "Kotlin, kotlinx" to "Apache-2.0",
    "LeakCanary plumber, logcat" to "Apache-2.0",
    "Manrope" to "OFL-1.1",
    "PGPainless" to "Apache-2.0",
    "Public Suffix List" to "MPL-2.0",
    "SLF4J" to "MIT",
    "sshj" to "Apache-2.0",
    "sublime-fuzzy" to "MIT",
    "ZXing, zxing-android-embedded" to "Apache-2.0",
  )

/** `0.3.0 (300)`, followed by the [commit] on the debug builds that have one. */
internal fun versionLine(name: String, code: Int, commit: String): String =
  if (commit.isEmpty()) "$name ($code)" else "$name ($code) · $commit"

/** The changelog at the tag of [versionName], without a suffix like `-debug`. */
internal fun changelogUrl(versionName: String): String =
  "$REPOSITORY/blob/v${versionName.substringBefore('-')}/CHANGELOG.md"

/** SHA-256 of [certificate] as `apksigner` and `keytool` users compare it, colon separated. */
internal fun certificateFingerprint(certificate: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(certificate).joinToString(":") { "%02X".format(it) }

/** The certificate this copy was really signed with, so it can be compared with the published. */
private fun Context.signingFingerprint(): String? =
  packageManager
    .getPackageInfoCompat(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    .signingInfo
    ?.apkContentsSigners
    ?.firstOrNull()
    ?.let { certificateFingerprint(it.toByteArray()) }

/** The about screen. Links leave for the browser: the app itself makes no connection for them. */
@Composable
fun AboutDestination(
  activity: FragmentActivity,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val fingerprint = remember { activity.signingFingerprint() }
  AboutScreen(
    version =
      versionLine(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.GIT_COMMIT),
    changelog = changelogUrl(BuildConfig.VERSION_NAME),
    fingerprint = fingerprint,
    onCopy = { activity.copyTextToClipboard(it, sensitive = false) },
    onOpen = { url ->
      try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
      } catch (_: ActivityNotFoundException) {
        activity.snackbar(message = activity.getString(R.string.about_no_browser))
      }
    },
    onBack = onBack,
    modifier = modifier,
  )
}

@Composable
private fun AboutScreen(
  version: String,
  changelog: String,
  fingerprint: String?,
  onCopy: (String) -> Unit,
  onOpen: (String) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsScaffold(stringResource(R.string.about), onBack, modifier) {
    Column(
      Modifier.fillMaxWidth().padding(SpacingMedium),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(SpacingMedium),
    ) {
      Box(Modifier.size(96.dp).clip(CircleShape)) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null)
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
      }
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
      Text(stringResource(R.string.about_summary), textAlign = TextAlign.Center)
    }
    AboutRow(stringResource(R.string.about_version), version) { onCopy(version) }
    LinkRow(stringResource(R.string.about_docs)) { onOpen(DOCUMENTATION) }
    LinkRow(stringResource(R.string.about_changelog)) { onOpen(changelog) }
    LinkRow(stringResource(R.string.about_source)) { onOpen(REPOSITORY) }
    LinkRow(stringResource(R.string.about_issues)) { onOpen("$REPOSITORY/issues") }
    // White in both themes: the logo's dark outline disappears on a dark background, and Ko-fi
    // asks that the logo itself stays as it is.
    Button(
      onClick = { onOpen(KO_FI) },
      modifier = Modifier.fillMaxWidth().padding(SpacingMedium),
      colors =
        ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
      Image(
        painterResource(R.drawable.ic_kofi_symbol),
        contentDescription = null,
        modifier = Modifier.height(24.dp),
      )
      Box(Modifier.width(SpacingMedium))
      Text(stringResource(R.string.about_support))
    }
    ListItem(
      headlineContent = { Text(stringResource(R.string.about_privacy_title)) },
      supportingContent = { Text(stringResource(R.string.about_privacy)) },
      colors = rowColors(),
    )
    if (fingerprint != null)
      ListItem(
        headlineContent = { Text(stringResource(R.string.about_certificate)) },
        supportingContent = { Text(fingerprint, fontFamily = FontFamily.Monospace) },
        modifier = Modifier.clickable(role = Role.Button) { onCopy(fingerprint) },
        colors = rowColors(),
      )
    AboutRow(stringResource(R.string.about_license), stringResource(R.string.about_license_text)) {
      onOpen("$REPOSITORY/blob/master/LICENSE.txt")
    }
    ListItem(
      headlineContent = { Text(stringResource(R.string.about_libraries)) },
      colors = rowColors(),
    )
    LICENSES.forEach { (name, license) ->
      ListItem(
        headlineContent = { Text(name) },
        supportingContent = { Text(license) },
        colors = rowColors(),
      )
    }
  }
}

@Composable
private fun AboutRow(
  title: String,
  summary: String? = null,
  trailing: (@Composable () -> Unit)? = null,
  onClick: () -> Unit,
) {
  ListItem(
    headlineContent = { Text(title) },
    supportingContent = summary?.let { { Text(it) } },
    trailingContent = trailing,
    modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    colors = rowColors(),
  )
}

/** A row that leaves for the browser, and says so. */
@Composable
private fun LinkRow(title: String, summary: String? = null, onClick: () -> Unit) {
  AboutRow(
    title = title,
    summary = summary,
    trailing = {
      Icon(
        painterResource(R.drawable.ic_open_in_new_24dp),
        contentDescription = stringResource(R.string.about_opens_browser),
      )
    },
    onClick = onClick,
  )
}
