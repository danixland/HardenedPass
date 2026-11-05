/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.passwordstore.BuildConfig
import app.passwordstore.R
import app.passwordstore.ui.compose.GroupedSurface
import app.passwordstore.util.features.Feature
import app.passwordstore.util.settings.Constants
import app.passwordstore.util.settings.PreferenceKeys
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.Serializable

/** The settings categories, one screen each. A route argument: kept from minification. */
@Keep
@Serializable
enum class SettingsSection(@StringRes val title: Int, @DrawableRes val icon: Int) {
  General(R.string.pref_category_general_title, R.drawable.app_settings_alt_24px),
  Autofill(R.string.pref_category_autofill_title, R.drawable.ic_wysiwyg_24px),
  Passwords(R.string.pref_category_passwords_title, R.drawable.ic_password_24px),
  Repository(R.string.pref_category_repository_title, R.drawable.ic_call_merge_24px),
  Misc(R.string.pref_category_misc_title, R.drawable.ic_miscellaneous_services_24px),
  Pgp(R.string.pref_category_pgp_title, R.drawable.ic_lock_open_24px),
}

/** What a row asks for beyond storing a value: another screen, a dialog or a picker. */
enum class SettingsAction {
  GitServer,
  Proxy,
  GitConfig,
  SshKey,
  SshKeyGen,
  KeyManager,
  ClearSecret,
  DeleteRepository,
  Export,
}

/** Top bar with [title] and back, then [content] in one grouped surface, scrolling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
  title: String,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  Scaffold(
    modifier = modifier,
    topBar = {
      TopAppBar(
        title = { Text(title) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.entry_back))
          }
        },
      )
    },
  ) { padding ->
    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
      GroupedSurface(content = content)
    }
  }
}

/** The settings categories. */
@Composable
fun SettingsScreen(
  onSection: (SettingsSection) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsScaffold(stringResource(R.string.action_settings), onBack, modifier) {
    SettingsSection.entries.forEach { section ->
      ListItem(
        headlineContent = { Text(stringResource(section.title)) },
        modifier = Modifier.clickable(role = Role.Button) { onSection(section) },
        leadingContent = { Icon(painterResource(section.icon), contentDescription = null) },
        colors = rowColors(),
      )
    }
  }
}

/**
 * One category's rows. Switches and values go out through [onBoolean] and [onString] with their
 * preference key (the destination decides which need a prompt first); everything else through
 * [onAction]. [canAuthenticate] and [autofillEnabled] come from the activity.
 */
@Composable
fun SettingsSectionScreen(
  section: SettingsSection,
  state: SettingsState,
  canAuthenticate: Boolean,
  autofillEnabled: Boolean,
  deleting: Boolean,
  onBoolean: (key: String, value: Boolean) -> Unit,
  onString: (key: String, value: String) -> Unit,
  onAction: (SettingsAction) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  SettingsScaffold(stringResource(section.title), onBack, modifier) {
    when (section) {
      SettingsSection.General -> {
        PreferenceChoice(
          title = stringResource(R.string.pref_app_theme_title),
          values = persistentListOf(*stringArrayResource(R.array.app_theme_values)),
          labels = persistentListOf(*stringArrayResource(R.array.app_theme_options)),
          selected = state.theme,
          onSelect = { onString(PreferenceKeys.APP_THEME, it) },
        )
        PreferenceChoice(
          title = stringResource(R.string.pref_directory_structure),
          values = persistentListOf(*stringArrayResource(R.array.directory_structure_values)),
          labels = persistentListOf(*stringArrayResource(R.array.directory_structure_entries)),
          selected = state.directoryStructure,
          onSelect = { onString(PreferenceKeys.DIRECTORY_STRUCTURE, it) },
        )
        PreferenceChoice(
          title = stringResource(R.string.pref_sort_order_title),
          values = persistentListOf(*stringArrayResource(R.array.sort_order_values)),
          labels = persistentListOf(*stringArrayResource(R.array.sort_order_entries)),
          selected = state.sortOrder,
          onSelect = { onString(PreferenceKeys.SORT_ORDER, it) },
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_disable_sync_on_pull_title),
          checked = state.disableSync,
          onCheckedChange = { onBoolean(PreferenceKeys.DISABLE_SYNC_ACTION, it) },
          summary = stringResource(R.string.pref_disable_sync_on_pull_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_recursive_filter_title),
          checked = state.filterRecursively,
          onCheckedChange = { onBoolean(PreferenceKeys.FILTER_RECURSIVELY, it) },
          summary = stringResource(R.string.pref_recursive_filter_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_search_on_start_title),
          checked = state.searchOnStart,
          onCheckedChange = { onBoolean(PreferenceKeys.SEARCH_ON_START, it) },
          summary = stringResource(R.string.pref_search_on_start_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_show_hidden_title),
          checked = state.showHidden,
          onCheckedChange = { onBoolean(PreferenceKeys.SHOW_HIDDEN_CONTENTS, it) },
          summary = stringResource(R.string.pref_show_hidden_summary),
        )
        // Enabled on Android 14 too (maintainer decision 4): upstream blocked it there for issue
        // 2802; if that platform bug shows on the maintainer's Android 14 tablet, the block
        // returns.
        PreferenceSwitch(
          title = stringResource(R.string.pref_biometric_auth_title),
          checked = state.biometric,
          onCheckedChange = { onBoolean(PreferenceKeys.BIOMETRIC_AUTH_2, it) },
          summary =
            stringResource(
              if (canAuthenticate) R.string.pref_biometric_auth_summary
              else R.string.pref_biometric_auth_summary_error
            ),
        )
      }
      SettingsSection.Autofill -> {
        PreferenceSwitch(
          title = stringResource(R.string.pref_autofill_enable_title),
          checked = autofillEnabled,
          onCheckedChange = { onBoolean(PreferenceKeys.AUTOFILL_ENABLE, it) },
        )
        PreferenceText(
          title = stringResource(R.string.preference_default_username_title),
          value = state.defaultUsername,
          onValueChange = { onString(PreferenceKeys.OREO_AUTOFILL_DEFAULT_USERNAME, it) },
          summary = stringResource(R.string.preference_default_username_summary),
          enabled = autofillEnabled,
        )
        PreferenceText(
          title = stringResource(R.string.preference_custom_public_suffixes_title),
          value = state.customSuffixes,
          onValueChange = { onString(PreferenceKeys.OREO_AUTOFILL_CUSTOM_PUBLIC_SUFFIXES, it) },
          summary = stringResource(R.string.preference_custom_public_suffixes_summary),
          hint = stringResource(R.string.preference_custom_public_suffixes_hint),
          singleLine = false,
          enabled = autofillEnabled,
        )
      }
      SettingsSection.Passwords -> {
        PreferenceChoice(
          title = stringResource(R.string.pref_password_generator_type_title),
          values = persistentListOf(*stringArrayResource(R.array.pwgen_provider_values)),
          labels = persistentListOf(*stringArrayResource(R.array.pwgen_provider_labels)),
          selected = state.pwgenType,
          onSelect = { onString(PreferenceKeys.PREF_KEY_PWGEN_TYPE, it) },
        )
        PreferenceText(
          title = stringResource(R.string.pref_clipboard_timeout_title),
          value = state.clipboardTimeout,
          onValueChange = { onString(PreferenceKeys.GENERAL_SHOW_TIME, it) },
          summary =
            stringResource(
              R.string.pref_clipboard_timeout_summary,
              state.clipboardTimeout.ifEmpty { Constants.DEFAULT_DECRYPTION_TIMEOUT.toString() },
            ),
          keyboardType = KeyboardType.Number,
        )
        PreferenceSwitch(
          title = stringResource(R.string.show_password_pref_title),
          checked = state.showPassword,
          onCheckedChange = { onBoolean(PreferenceKeys.SHOW_PASSWORD, it) },
          summary = stringResource(R.string.show_password_pref_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_copy_title),
          checked = state.copyOnDecrypt,
          onCheckedChange = { onBoolean(PreferenceKeys.COPY_ON_DECRYPT, it) },
          summary = stringResource(R.string.pref_copy_summary),
        )
      }
      SettingsSection.Repository -> {
        PreferenceSwitch(
          title = stringResource(R.string.pref_rebase_on_pull_title),
          checked = state.rebaseOnPull,
          onCheckedChange = { onBoolean(PreferenceKeys.REBASE_ON_PULL, it) },
          summary =
            stringResource(
              if (state.rebaseOnPull) R.string.pref_rebase_on_pull_summary_on
              else R.string.pref_rebase_on_pull_summary
            ),
        )
        if (state.isGitRepo) {
          PreferenceAction(
            title = stringResource(R.string.pref_edit_git_server_settings),
            onClick = { onAction(SettingsAction.GitServer) },
          )
          if (state.httpsRemote)
            PreferenceAction(
              title = stringResource(R.string.pref_edit_proxy_settings),
              onClick = { onAction(SettingsAction.Proxy) },
            )
          PreferenceAction(
            title = stringResource(R.string.pref_edit_git_config),
            onClick = { onAction(SettingsAction.GitConfig) },
          )
          PreferenceAction(
            title = stringResource(R.string.pref_import_ssh_key_title),
            onClick = { onAction(SettingsAction.SshKey) },
          )
        }
        PreferenceAction(
          title = stringResource(R.string.pref_ssh_keygen_title),
          onClick = { onAction(SettingsAction.SshKeyGen) },
        )
        if (state.isGitRepo && state.canShowSshKey)
          PreferenceAction(
            title = stringResource(R.string.pref_ssh_see_key_title),
            onClick = { onAction(SettingsAction.SshKey) },
          )
        state.savedSecret?.let { title ->
          PreferenceAction(
            title = stringResource(title),
            onClick = { onAction(SettingsAction.ClearSecret) },
          )
        }
        PreferenceAction(
          title = stringResource(R.string.pref_git_delete_repo_title),
          onClick = { onAction(SettingsAction.DeleteRepository) },
          summary = stringResource(R.string.pref_git_delete_repo_summary),
          enabled = !deleting,
        )
      }
      SettingsSection.Misc -> {
        PreferenceAction(
          title = stringResource(R.string.prefs_export_passwords_title),
          onClick = { onAction(SettingsAction.Export) },
          summary = stringResource(R.string.prefs_export_passwords_summary),
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_clear_clipboard_title),
          checked = state.clearClipboardHistory,
          onCheckedChange = { onBoolean(PreferenceKeys.CLEAR_CLIPBOARD_HISTORY, it) },
          summary = stringResource(R.string.pref_clear_clipboard_summary),
        )
        if (!BuildConfig.DEBUG)
          PreferenceSwitch(
            title = stringResource(R.string.pref_debug_logging_title),
            checked = state.debugLogging,
            onCheckedChange = { onBoolean(PreferenceKeys.ENABLE_DEBUG_LOGGING, it) },
            summary = stringResource(R.string.pref_debug_logging_summary),
          )
      }
      SettingsSection.Pgp -> {
        PreferenceAction(
          title = stringResource(R.string.pref_pgp_key_manager_title),
          onClick = { onAction(SettingsAction.KeyManager) },
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_pgp_ascii_armor_title),
          checked = state.asciiArmor,
          onCheckedChange = { onBoolean(PreferenceKeys.ASCII_ARMOR, it) },
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_passphrase_cache_title),
          checked = state.passphraseCache,
          onCheckedChange = { onBoolean(Feature.EnablePGPPassphraseCache.configKey, it) },
          summary = stringResource(R.string.pref_passphrase_cache_summary),
          // Without a screen lock it can still be turned off.
          enabled = canAuthenticate || state.passphraseCache,
        )
        PreferenceSwitch(
          title = stringResource(R.string.pref_passphrase_cache_auto_clear_title),
          checked = state.autoClearCache,
          onCheckedChange = { onBoolean(PreferenceKeys.CLEAR_PASSPHRASE_CACHE, it) },
          summary = stringResource(R.string.pref_passphrase_cache_auto_clear_summary),
          enabled = state.passphraseCache,
        )
      }
    }
  }
}
