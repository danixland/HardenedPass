/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.injection.prefs

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import app.passwordstore.BuildConfig
import app.passwordstore.util.crypto.keystorePreferences
import app.passwordstore.util.extensions.getEncryptedGitPrefs
import dagger.Module
import dagger.Provides
import dagger.Reusable
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
class PreferenceModule {

  @[Provides PasswordGeneratorPreferences Reusable]
  fun providePwgenPreferences(@ApplicationContext context: Context): SharedPreferences {
    return context.getSharedPreferences("pwgen", MODE_PRIVATE)
  }

  @[Provides SettingsPreferences Reusable]
  fun provideSettingsPreferences(@ApplicationContext context: Context): SharedPreferences {
    return context.getSharedPreferences("${BuildConfig.APPLICATION_ID}_preferences", MODE_PRIVATE)
  }

  @[Provides GitPreferences Reusable]
  fun provideGitPreferences(@ApplicationContext context: Context): SharedPreferences {
    return context.getEncryptedGitPrefs()
  }

  @[Provides ProxyPreferences Reusable]
  fun provideProxyPreferences(@ApplicationContext context: Context): SharedPreferences {
    return context.keystorePreferences("proxy_secrets")
  }
}
