/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingLargest

/**
 * Warns that the app being filled ([appName], installed [installed]) is signed by another publisher
 * than when an entry was first matched to it. [onKeepDisabled] fills nothing. [onReset] forgets the
 * old matches and fills; it is offered only after "Advanced information", which shows [details]
 * (package and certificate hash), so re-enabling always takes two deliberate taps.
 */
@Composable
fun PublisherChangedScreen(
  appName: String,
  installed: String,
  details: String,
  onKeepDisabled: () -> Unit,
  onReset: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var advanced by rememberSaveable { mutableStateOf(false) }
  val center = Modifier.fillMaxWidth()
  Surface(modifier) {
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(SpacingLargest),
      verticalArrangement = Arrangement.spacedBy(SpacingLarge),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        painterResource(R.drawable.ic_warning_red_24dp),
        contentDescription =
          stringResource(R.string.oreo_autofill_warning_publisher_warning_sign_description),
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.error,
      )
      Text(
        stringResource(R.string.oreo_autofill_warning_publisher_dataset_title),
        modifier = center.semantics { heading() },
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleLarge,
      )
      Text(
        stringResource(R.string.oreo_autofill_warning_publisher_header),
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        appName,
        modifier = center,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleMedium,
      )
      Text(
        installed,
        modifier = center,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
      )
      // ponytail: stringResource drops the footer's <b> in every locale but Italian (whose footer
      // has none); the red title carries the emphasis. Keep spans with getText if it is missed.
      Text(
        stringResource(R.string.oreo_autofill_warning_publisher_footer),
        style = MaterialTheme.typography.bodyMedium,
      )
      Button(onClick = onKeepDisabled, modifier = center) {
        Text(
          stringResource(R.string.oreo_autofill_warning_publisher_changed_disable_autofill_button)
        )
      }
      if (!advanced) {
        OutlinedButton(onClick = { advanced = true }, modifier = center) {
          Text(stringResource(R.string.oreo_autofill_warning_publisher_advanced_info_button))
        }
      } else {
        // The details come first, so Re-enable never sits where Advanced information was and a
        // double tap cannot re-enable autofill.
        SelectionContainer {
          Text(
            details,
            modifier = center,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          )
        }
        OutlinedButton(onClick = onReset, modifier = center) {
          Text(stringResource(R.string.oreo_autofill_warning_publisher_reenable_button))
        }
      }
    }
  }
}
