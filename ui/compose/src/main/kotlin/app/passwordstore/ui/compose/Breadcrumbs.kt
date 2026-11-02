/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.passwordstore.ui.compose.preview.ThemePreviews
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingSmall

/** One breadcrumb chip. The root crumb has an empty [label] and [path]. */
public data class Crumb(val label: String, val path: String)

/** Crumbs for a folder at [relativePath] inside the store: root first, then each level. */
public fun crumbsFor(relativePath: String): List<Crumb> {
  val parts = relativePath.split('/').filter { it.isNotEmpty() }
  return listOf(Crumb("", "")) +
    parts.indices.map { i -> Crumb(parts[i], parts.subList(0, i + 1).joinToString("/")) }
}

/**
 * Folder location as chips. The last crumb is highlighted; tapping any crumb reports its path.
 * [rootDescription] is the content description of the root (home) chip.
 */
@Composable
@Suppress("ComposeUnstableCollections")
public fun BreadcrumbBar(
  crumbs: List<Crumb>,
  rootDescription: String,
  onCrumbClick: (path: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SpacingLarge),
    horizontalArrangement = Arrangement.spacedBy(SpacingSmall),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    crumbs.forEachIndexed { i, crumb ->
      if (i > 0) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
      val current = i == crumbs.lastIndex
      AssistChip(
        onClick = { onCrumbClick(crumb.path) },
        label = {
          if (crumb.path.isEmpty()) Icon(Icons.Filled.Home, contentDescription = rootDescription)
          else Text(crumb.label)
        },
        colors =
          if (current)
            AssistChipDefaults.assistChipColors(
              containerColor = MaterialTheme.colorScheme.secondaryContainer,
              labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
          else AssistChipDefaults.assistChipColors(),
        border = null,
      )
    }
  }
}

@ThemePreviews
@Composable
private fun BreadcrumbBarPreview() {
  APSTheme { BreadcrumbBar(crumbsFor("work/servers"), "Store root", onCrumbClick = {}) }
}
