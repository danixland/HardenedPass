/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import app.passwordstore.R
import app.passwordstore.ui.compose.FieldRow
import app.passwordstore.ui.compose.FieldRowLabels
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.LetterAvatar
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/**
 * One entry: header (avatar, name, [folder] relative to the store root, "" at the root), then the
 * password, username and one-time code, then the extra fields. [fields] is null until decrypted;
 * [otp] is null when the entry has no TOTP. [onCopySecret] (password, one-time code) is separate
 * from [onCopy] because only those copies start the clipboard clear timer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryScreen(
  name: String,
  folder: String,
  fields: EntryFields?,
  otp: String?,
  otpSecondsLeft: Long,
  otpFractionLeft: Float,
  showPassword: Boolean,
  onBack: () -> Unit,
  onEdit: () -> Unit,
  onShare: () -> Unit,
  onCopyName: () -> Unit,
  onCopySecret: (String) -> Unit,
  onCopy: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var menu by remember { mutableStateOf(false) }
  val labels =
    FieldRowLabels(
      copy = stringResource(R.string.copy_label),
      show = stringResource(R.string.entry_show_password),
      hide = stringResource(R.string.entry_hide_password),
    )
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {},
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
        actions = {
          if (fields != null)
            IconButton(onClick = onEdit) {
              Icon(
                painterResource(R.drawable.ic_edit_24dp),
                contentDescription = stringResource(R.string.edit_password),
              )
            }
          Box {
            IconButton(onClick = { menu = true }) {
              Icon(Icons.Filled.MoreVert, stringResource(R.string.list_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.entry_copy_name)) },
                onClick = {
                  menu = false
                  onCopyName()
                },
              )
              if (fields?.password != null)
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.share_as_plaintext)) },
                  onClick = {
                    menu = false
                    onShare()
                  },
                  leadingIcon = {
                    Icon(painterResource(R.drawable.ic_share_24dp), contentDescription = null)
                  },
                )
            }
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = SpacingLarge, vertical = SpacingMedium),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LetterAvatar(name)
        Column(Modifier.weight(1f).padding(start = SpacingLarge)) {
          Text(name, style = MaterialTheme.typography.titleLarge)
          Text(
            if (folder.isEmpty()) stringResource(R.string.list_store_root) else folder,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      if (fields != null) {
        if (fields.password != null || fields.username != null || otp != null)
          GroupedSurface(Modifier.fillMaxWidth()) {
            if (fields.password != null)
              FieldRow(
                label = stringResource(R.string.password),
                value = fields.password,
                labels = labels.copy(copy = stringResource(R.string.copy_password)),
                masked = true,
                initiallyRevealed = showPassword,
                onCopy = { onCopySecret(fields.password) },
              )
            if (fields.username != null)
              FieldRow(
                label = stringResource(R.string.username),
                value = fields.username,
                labels = labels,
                onCopy = { onCopy(fields.username) },
              )
            if (otp != null) {
              FieldRow(
                label = stringResource(R.string.otp_label_format, otpSecondsLeft),
                value = otp,
                labels = labels,
                onCopy = { onCopySecret(otp) },
              )
              LinearProgressIndicator(
                progress = { otpFractionLeft },
                modifier =
                  Modifier.fillMaxWidth()
                    .padding(start = SpacingLarge, end = SpacingLarge, bottom = SpacingMedium),
              )
            }
          }
        if (fields.extras.isNotEmpty())
          GroupedSurface(Modifier.fillMaxWidth()) {
            fields.extras.forEach { (label, value) ->
              key(label) { FieldRow(label, value, labels, onCopy = { onCopy(value) }) }
            }
          }
      }
    }
  }
}
