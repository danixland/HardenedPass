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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.passwordstore.ui.compose.preview.ThemePreviews
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium

/** Content descriptions for [FieldRow]'s buttons, supplied by the app's translated strings. */
public data class FieldRowLabels(val copy: String, val show: String, val hide: String)

/** Masked values (passwords) use a monospace face, so 0 and O, l and 1 stay apart. */
private val Monospace = FontFamily(Font(R.font.jetbrainsmono_nl_regular))

/**
 * A labelled value with an optional copy button. When [masked] is true the value is hidden behind
 * dots until the reveal toggle is pressed, unless [initiallyRevealed] shows it from the start.
 */
@Composable
public fun FieldRow(
  label: String,
  value: String,
  labels: FieldRowLabels,
  modifier: Modifier = Modifier,
  masked: Boolean = false,
  initiallyRevealed: Boolean = false,
  onCopy: (() -> Unit)? = null,
) {
  var revealed by rememberSaveable { mutableStateOf(initiallyRevealed) }
  Row(
    modifier
      .fillMaxWidth()
      .padding(
        start = SpacingLarge,
        end = SpacingMedium,
        top = SpacingMedium,
        bottom = SpacingMedium,
      ),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(Modifier.weight(1f)) {
      Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      val style = MaterialTheme.typography.bodyLarge
      Text(
        if (masked && !revealed) "•".repeat(10) else value,
        style = if (masked) style.copy(fontFamily = Monospace) else style,
      )
    }
    if (masked)
      IconButton(onClick = { revealed = !revealed }) {
        Icon(
          painterResource(
            if (revealed) R.drawable.baseline_visibility_off_24
            else R.drawable.baseline_visibility_24
          ),
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
