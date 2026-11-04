/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.autofill

import app.passwordstore.ui.list.ListRow
import app.passwordstore.util.auth.BiometricAuthenticator.Result as BiometricResult
import app.passwordstore.util.settings.DirectoryStructure
import java.io.File
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * The autofill picker's rows for [files] (entries under [root], already ranked), in their order.
 * The name is the origin identifier per [structure], the part that must match the site or app, so
 * it stands out as the old picker's bold part did; the subtitle holds the account part and the
 * folders above the identifier. Without an identifier the account part is the name.
 */
fun autofillRows(
  files: List<File>,
  root: File,
  structure: DirectoryStructure,
): ImmutableList<ListRow> =
  files
    .map { file ->
      val relative = file.relativeTo(root)
      val identifier = structure.getIdentifierFor(relative)
      val account = structure.getAccountPartFor(relative)
      val subtitle =
        if (identifier == null) null
        else
          listOfNotNull(account, structure.getPathToIdentifierFor(relative))
            .joinToString(" · ")
            .ifEmpty { null }
      ListRow(file.absolutePath, identifier ?: account ?: file.nameWithoutExtension, subtitle, 0)
    }
    .toImmutableList()

/**
 * Whether autofill may cache the passphrase it just decrypted with: only with the cache feature on
 * ([cacheEnabled]) and after a successful screen lock prompt ([auth]), as the entry view does. A
 * passphrase typed after a cancelled, failed or unavailable prompt is never kept.
 */
fun cachesPassphrase(cacheEnabled: Boolean, auth: BiometricResult): Boolean =
  cacheEnabled && auth is BiometricResult.Success
