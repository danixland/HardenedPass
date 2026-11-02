/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.compose

/** One breadcrumb chip. The root crumb has an empty [label] and [path]. */
public data class Crumb(val label: String, val path: String)

/** Crumbs for a folder at [relativePath] inside the store: root first, then each level. */
public fun crumbsFor(relativePath: String): List<Crumb> {
  val parts = relativePath.split('/').filter { it.isNotEmpty() }
  return listOf(Crumb("", "")) +
    parts.indices.map { i -> Crumb(parts[i], parts.subList(0, i + 1).joinToString("/")) }
}
