/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.entry

import androidx.compose.runtime.Immutable
import app.passwordstore.data.passfile.PasswordEntry
import app.passwordstore.data.passfile.Totp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/**
 * What the entry view shows. [password] and [username] open the first group (the one-time code
 * joins them from the entry's TOTP flow); [extras] are the remaining `key: value` lines in file
 * order, plus lines that are not `key: value` under the parser's "Extra Content" key. Blank values
 * are null, so their rows are not shown.
 */
@Immutable
data class EntryFields(
  val password: String?,
  val username: String?,
  val extras: ImmutableList<Pair<String, String>>,
)

fun entryFields(entry: PasswordEntry): EntryFields =
  EntryFields(
    password = entry.password?.takeIf { it.isNotBlank() },
    username = entry.username?.takeIf { it.isNotBlank() },
    extras = entry.extraContent.toList().toImmutableList(),
  )

/** Share of the code's lifetime left: 1 right after it was generated, toward 0 at expiry. */
fun Totp.fractionLeft(): Float = (remainingTime / period).toFloat()
