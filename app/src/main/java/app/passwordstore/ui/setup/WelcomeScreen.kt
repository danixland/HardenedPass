/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingLargest

/** First start without a store: clone one, create a local one, or open the settings. */
@Composable
fun WelcomeScreen(
  onClone: () -> Unit,
  onCreate: () -> Unit,
  onSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(modifier = modifier) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(SpacingLargest),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Image(
        painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(144.dp),
      )
      Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
      Text(
        stringResource(R.string.select_repo_type_text),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
      )
      Button(onClick = onClone, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.clone_remote_repo))
      }
      OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.create_local_repo))
      }
      TextButton(onClick = onSettings) { Text(stringResource(R.string.action_settings)) }
    }
  }
}

/**
 * A plain dialog for the setup screens: optional [title], [text], [confirm] and, when [dismiss] is
 * given, a second button running [onDismissButton] (by default the same as dismissing).
 */
@Composable
internal fun SetupDialog(
  text: String,
  confirm: String,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  title: String? = null,
  dismiss: String? = null,
  onDismissButton: () -> Unit = onDismiss,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    title =
      if (title != null) {
        { Text(title) }
      } else null,
    text = { Text(text) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
    dismissButton =
      if (dismiss != null) {
        { TextButton(onClick = onDismissButton) { Text(dismiss) } }
      } else null,
  )
}
