/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import kotlinx.collections.immutable.ImmutableList

private const val DISABLED_ALPHA = 0.38f

/** List item colors for rows inside a grouped surface: the surface shows through. */
@Composable
internal fun rowColors(): ListItemColors =
  ListItemDefaults.colors(containerColor = Color.Transparent)

private fun Modifier.dimmed(enabled: Boolean) = if (enabled) this else alpha(DISABLED_ALPHA)

/** A row that runs [onClick]: opens a screen or a dialog. */
@Composable
fun PreferenceAction(
  title: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
) {
  ListItem(
    headlineContent = { Text(title) },
    modifier =
      modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).dimmed(enabled),
    supportingContent =
      if (summary != null) {
        { Text(summary) }
      } else null,
    colors = rowColors(),
  )
}

/**
 * A switch row showing [checked]. [onCheckedChange] gets the requested value; the caller decides
 * whether it is stored (a screen lock prompt may come first), and the row shows what was stored.
 */
@Composable
fun PreferenceSwitch(
  title: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  enabled: Boolean = true,
) {
  ListItem(
    headlineContent = { Text(title) },
    modifier =
      modifier
        .toggleable(
          value = checked,
          enabled = enabled,
          role = Role.Switch,
          onValueChange = onCheckedChange,
        )
        .dimmed(enabled),
    supportingContent =
      if (summary != null) {
        { Text(summary) }
      } else null,
    trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
    colors = rowColors(),
  )
}

/**
 * A single choice among [values], shown by their [labels] (same order); the summary is the selected
 * label. Tapping opens a dialog of radio buttons; choosing one closes it.
 */
@Composable
fun PreferenceChoice(
  title: String,
  values: ImmutableList<String>,
  labels: ImmutableList<String>,
  selected: String,
  onSelect: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var open by rememberSaveable { mutableStateOf(false) }
  PreferenceAction(
    title = title,
    onClick = { open = true },
    modifier = modifier,
    summary = labels.getOrNull(values.indexOf(selected)),
  )
  if (open)
    AlertDialog(
      onDismissRequest = { open = false },
      title = { Text(title) },
      text = {
        Column(Modifier.selectableGroup()) {
          values.forEachIndexed { index, value ->
            Row(
              Modifier.fillMaxWidth()
                .selectable(
                  selected = value == selected,
                  role = Role.RadioButton,
                  onClick = {
                    open = false
                    onSelect(value)
                  },
                )
                .padding(vertical = SpacingMedium),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = value == selected, onClick = null)
              Text(labels[index], Modifier.padding(start = SpacingLarge))
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { open = false }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
}

/**
 * A text value; tapping opens a dialog to edit it. The draft is kept across recreation in saved
 * state: never use this row for a secret. [KeyboardType.Number] keeps digits only.
 */
@Composable
fun PreferenceText(
  title: String,
  value: String,
  onValueChange: (String) -> Unit,
  modifier: Modifier = Modifier,
  summary: String? = null,
  hint: String? = null,
  singleLine: Boolean = true,
  keyboardType: KeyboardType = KeyboardType.Text,
  enabled: Boolean = true,
) {
  var draft by rememberSaveable { mutableStateOf<String?>(null) }
  PreferenceAction(
    title = title,
    onClick = { draft = value },
    modifier = modifier,
    summary = summary,
    enabled = enabled,
  )
  draft?.let { text ->
    AlertDialog(
      onDismissRequest = { draft = null },
      title = { Text(title) },
      text = {
        OutlinedTextField(
          value = text,
          onValueChange = { new ->
            draft = if (keyboardType == KeyboardType.Number) new.filter(Char::isDigit) else new
          },
          modifier = Modifier.fillMaxWidth(),
          placeholder =
            if (hint != null) {
              { Text(hint) }
            } else null,
          singleLine = singleLine,
          minLines = if (singleLine) 1 else 3,
          keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            draft = null
            onValueChange(text)
          }
        ) {
          Text(stringResource(R.string.dialog_ok))
        }
      },
      dismissButton = {
        TextButton(onClick = { draft = null }) { Text(stringResource(R.string.dialog_cancel)) }
      },
    )
  }
}
