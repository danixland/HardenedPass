/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import app.passwordstore.R
import app.passwordstore.ui.compose.R as composeR
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.main.MainActivity

/** Proxy host, port, user name and password; [hostError] and [portError] block the save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyScreen(
  host: String,
  port: String,
  username: String,
  password: String,
  hostError: Boolean,
  portError: Boolean,
  onHostChange: (String) -> Unit,
  onPortChange: (String) -> Unit,
  onUsernameChange: (String) -> Unit,
  onPasswordChange: (String) -> Unit,
  onSave: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var revealed by remember { mutableStateOf(false) }
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.activity_proxy_label)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(
      Modifier.padding(padding)
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
    ) {
      val field = Modifier.fillMaxWidth()
      OutlinedTextField(
        value = host,
        onValueChange = onHostChange,
        modifier = field,
        label = { Text(stringResource(R.string.proxy_hostname)) },
        supportingText =
          if (hostError) {
            { Text(stringResource(R.string.invalid_proxy_url)) }
          } else null,
        isError = hostError,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
          ),
      )
      OutlinedTextField(
        value = port,
        onValueChange = { onPortChange(it.filter(Char::isDigit)) },
        modifier = field,
        label = { Text(stringResource(R.string.port)) },
        supportingText =
          if (portError) {
            { Text(stringResource(R.string.invalid_proxy_port)) }
          } else null,
        isError = portError,
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        modifier = field,
        label = { Text(stringResource(R.string.username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
      )
      OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        modifier = field,
        label = { Text(stringResource(R.string.password)) },
        trailingIcon = {
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
        },
        visualTransformation =
          if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions =
          KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
          ),
      )
      Button(onClick = onSave, modifier = field, enabled = !hostError && !portError) {
        Text(stringResource(R.string.crypto_save))
      }
    }
  }
}

/**
 * The proxy screen. Holds FLAG_SECURE while shown: it shows the saved proxy password (masked, with
 * a reveal toggle).
 */
@Composable
fun ProxyDestination(
  activity: MainActivity,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: ProxyViewModel = hiltViewModel(),
) {
  DisposableEffect(activity) {
    activity.holdSecure()
    onDispose { activity.releaseSecure() }
  }
  ProxyScreen(
    host = viewModel.host,
    port = viewModel.port,
    username = viewModel.username,
    password = viewModel.password,
    hostError = viewModel.hostError,
    portError = viewModel.portError,
    onHostChange = { viewModel.host = it },
    onPortChange = { viewModel.port = it },
    onUsernameChange = { viewModel.username = it },
    onPasswordChange = { viewModel.password = it },
    onSave = {
      if (viewModel.save()) {
        Toast.makeText(activity, R.string.git_server_config_save_success, Toast.LENGTH_SHORT).show()
        onClose()
      }
    },
    onBack = onClose,
    modifier = modifier,
  )
}
