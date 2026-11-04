/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.data.passfile

import kotlin.time.Duration

/** A TOTP code, how long it stays valid, and the full period it was generated for. */
public data class Totp(
  public val value: String,
  public val remainingTime: Duration,
  public val period: Duration,
)
