/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.injection.totp

import app.passwordstore.util.totp.TotpFinder
import app.passwordstore.util.totp.UriTotpFinder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ActivityComponent::class, ViewModelComponent::class)
interface TotpModule {
  @Binds fun bindTotpFinder(impl: UriTotpFinder): TotpFinder
}
