/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import app.passwordstore.R
import app.passwordstore.ui.compose.BreadcrumbBar
import app.passwordstore.ui.compose.R as composeR
import app.passwordstore.ui.compose.crumbsFor
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Which field takes focus when the editor first opens. */
enum class EditorFocus {
  None,
  Name,
  Username,
}

/**
 * The editor: name, folder (breadcrumbs; they and "Change" open the picker), password with reveal
 * and generator, username, free-text extra content (the pass format as text) and the OTP actions,
 * shown while the extras hold no TOTP secret ([showOtpActions]). [onScanQr] is null on a device
 * without a camera. Only the reveal toggle and the menus keep state here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
  form: EditorForm,
  editing: Boolean,
  saving: Boolean,
  showOtpActions: Boolean,
  initiallyRevealed: Boolean,
  focus: EditorFocus,
  onFormChange: (EditorForm) -> Unit,
  onClose: () -> Unit,
  onSave: (copy: Boolean) -> Unit,
  onChangeFolder: () -> Unit,
  onGenerate: () -> Unit,
  onScanQr: (() -> Unit)?,
  onQrImage: () -> Unit,
  onManualOtp: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var revealed by remember { mutableStateOf(initiallyRevealed) }
  var menu by remember { mutableStateOf(false) }
  var qrMenu by remember { mutableStateOf(false) }
  val nameFocus = remember { FocusRequester() }
  val usernameFocus = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    when (focus) {
      EditorFocus.Name -> nameFocus.requestFocus()
      EditorFocus.Username -> usernameFocus.requestFocus()
      EditorFocus.None -> Unit
    }
  }
  val field = Modifier.fillMaxWidth().padding(horizontal = SpacingLarge)
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = {
          Text(
            stringResource(if (editing) R.string.editor_edit_title else R.string.editor_new_title)
          )
        },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, stringResource(R.string.editor_close))
          }
        },
        actions = {
          TextButton(onClick = { onSave(false) }, enabled = !saving) {
            Text(stringResource(R.string.crypto_save))
          }
          Box {
            IconButton(onClick = { menu = true }) {
              Icon(Icons.Filled.MoreVert, stringResource(R.string.list_more_options))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
              DropdownMenuItem(
                text = { Text(stringResource(R.string.crypto_save_and_copy)) },
                onClick = {
                  menu = false
                  onSave(true)
                },
                enabled = !saving,
              )
            }
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(bottom = SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingMedium),
    ) {
      OutlinedTextField(
        value = form.name,
        onValueChange = { onFormChange(form.copy(name = it)) },
        modifier = field.focusRequester(nameFocus),
        label = { Text(stringResource(R.string.crypto_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      Text(
        stringResource(R.string.editor_folder),
        field,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        BreadcrumbBar(
          crumbsFor(form.dir),
          stringResource(R.string.list_store_root),
          onCrumbClick = { onChangeFolder() },
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChangeFolder, modifier = Modifier.padding(end = SpacingMedium)) {
          Text(stringResource(R.string.editor_change_folder))
        }
      }
      OutlinedTextField(
        value = form.password,
        onValueChange = { onFormChange(form.copy(password = it)) },
        modifier = field,
        label = { Text(stringResource(R.string.crypto_pass_label)) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        visualTransformation =
          if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Next,
          ),
        trailingIcon = {
          Row {
            IconButton(onClick = { revealed = !revealed }) {
              Icon(
                painterResource(
                  if (revealed) composeR.drawable.baseline_visibility_off_24
                  else composeR.drawable.baseline_visibility_24
                ),
                contentDescription =
                  stringResource(
                    if (revealed) R.string.entry_hide_password else R.string.entry_show_password
                  ),
              )
            }
            IconButton(onClick = onGenerate) {
              Icon(
                painterResource(R.drawable.ic_password_24px),
                contentDescription = stringResource(R.string.pwgen_title),
              )
            }
          }
        },
      )
      OutlinedTextField(
        value = form.username,
        onValueChange = { onFormChange(form.copy(username = it)) },
        modifier = field.focusRequester(usernameFocus),
        label = { Text(stringResource(R.string.crypto_username_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = form.extra,
        onValueChange = { onFormChange(form.copy(extra = it)) },
        modifier = field,
        label = { Text(stringResource(R.string.crypto_extra_label)) },
        minLines = 3,
        // A password keyboard does not learn what is typed, as the old textVisiblePassword.
        keyboardOptions =
          KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
      )
      if (showOtpActions)
        Row(field, horizontalArrangement = Arrangement.spacedBy(SpacingMedium)) {
          Box {
            OutlinedButton(onClick = { qrMenu = true }) {
              Icon(painterResource(R.drawable.ic_qr_code_scanner), contentDescription = null)
              Text(stringResource(R.string.editor_otp_qr), Modifier.padding(start = SpacingMedium))
            }
            DropdownMenu(expanded = qrMenu, onDismissRequest = { qrMenu = false }) {
              if (onScanQr != null)
                DropdownMenuItem(
                  text = { Text(stringResource(R.string.otp_import_qr_code)) },
                  onClick = {
                    qrMenu = false
                    onScanQr()
                  },
                )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.otp_import_from_file)) },
                onClick = {
                  qrMenu = false
                  onQrImage()
                },
              )
            }
          }
          OutlinedButton(onClick = onManualOtp) { Text(stringResource(R.string.editor_otp_manual)) }
        }
    }
  }
}

/**
 * Asks for a TOTP secret and an optional account (the URI's issuer), as the old manual OTP dialog
 * did. Both live in [remember] only, and the window is FLAG_SECURE.
 */
@Composable
fun OtpDialog(
  onAdd: (secret: String, account: String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var secret by remember { mutableStateOf("") }
  var account by remember { mutableStateOf("") }
  val focus = remember { FocusRequester() }
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = modifier,
    properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    title = { Text(stringResource(R.string.add_otp)) },
    text = {
      Column {
        OutlinedTextField(
          value = secret,
          onValueChange = { secret = it },
          modifier = Modifier.focusRequester(focus),
          label = { Text(stringResource(R.string.otp_import_manual_hint_secret)) },
          singleLine = true,
          keyboardOptions =
            KeyboardOptions(
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Next,
            ),
        )
        OutlinedTextField(
          value = account,
          onValueChange = { account = it },
          label = { Text(stringResource(R.string.otp_import_manual_hint_account)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        )
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = {
      TextButton(onClick = { onAdd(secret, account) }, enabled = secret.isNotBlank()) {
        Text(stringResource(R.string.dialog_ok))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
    },
  )
}
