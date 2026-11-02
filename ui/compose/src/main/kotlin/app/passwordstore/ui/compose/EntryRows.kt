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
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  Row(
    modifier =
      modifier
        .fillMaxWidth()
        .background(
          if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
        )
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
