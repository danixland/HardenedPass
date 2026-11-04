/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.passwordstore.R
import app.passwordstore.ui.compose.EntryRow
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.list.ListRow
import app.passwordstore.ui.settings.PreferenceSwitch
import kotlinx.collections.immutable.ImmutableList

/**
 * The autofill picker: which site or app is being filled ([origin], as the user can check it), a
 * search over the store's entries, the matching [rows] (null while the first search runs), and the
 * switches for phishing-resistant search ([showStrict]: websites only), recording the pick as a
 * match ([matchLabel]) and clearing earlier matches. [onPick] takes the entry's absolute path.
 */
@Composable
fun AutofillFilterScreen(
  origin: String,
  query: String,
  rows: ImmutableList<ListRow>?,
  showStrict: Boolean,
  strict: Boolean,
  matchLabel: String,
  match: Boolean,
  clear: Boolean,
  onQueryChange: (String) -> Unit,
  onStrictChange: (Boolean) -> Unit,
  onMatchChange: (Boolean) -> Unit,
  onClearChange: (Boolean) -> Unit,
  onPick: (path: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val fillInto = stringResource(R.string.oreo_autofill_select_and_fill_into)
  val listState = rememberLazyListState()
  // As the old picker did: every new result starts at its best match.
  LaunchedEffect(rows) { listState.scrollToItem(0) }
  Surface(modifier.fillMaxSize()) {
    Column(Modifier.padding(vertical = SpacingLarge)) {
      Image(
        painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = stringResource(R.string.app_name),
        modifier = Modifier.size(60.dp).align(Alignment.CenterHorizontally),
      )
      Text(
        buildAnnotatedString {
          append(fillInto)
          append("\n")
          withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(origin) }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = SpacingLarge),
        textAlign = TextAlign.Center,
      )
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(SpacingLarge),
        label = { Text(stringResource(R.string.action_search)) },
        leadingIcon = {
          Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = null)
        },
        trailingIcon =
          if (query.isNotEmpty()) {
            {
              IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, stringResource(R.string.list_clear_search))
              }
            }
          } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      )
      Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
          rows == null -> Unit
          rows.isEmpty() ->
            Text(
              stringResource(R.string.oreo_autofill_filter_no_results),
              modifier = Modifier.padding(horizontal = SpacingLarge),
              style = MaterialTheme.typography.titleMedium,
            )
          else ->
            // One lazy item per row: a cleared search lists the whole store. The surface wraps
            // a short list and is capped by the box for a long one.
            GroupedSurface(Modifier.fillMaxWidth()) {
              LazyColumn(state = listState) {
                items(rows, key = { it.path }) { row ->
                  EntryRow(
                    name = row.label,
                    subtitle = row.subtitle,
                    onClick = { onPick(row.path) },
                  )
                }
              }
            }
        }
      }
      if (showStrict)
        PreferenceSwitch(
          stringResource(R.string.oreo_autofill_strict_domain_search),
          strict,
          onStrictChange,
        )
      PreferenceSwitch(matchLabel, match, onMatchChange)
      PreferenceSwitch(
        stringResource(R.string.oreo_autofill_matches_clear_existing),
        clear,
        onClearChange,
      )
    }
  }
}
