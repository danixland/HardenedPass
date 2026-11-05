/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.passwordstore.ui.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.autofill.AutofillManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.passwordstore.R
import app.passwordstore.data.repo.PasswordRepository
import app.passwordstore.ui.compose.theme.APSTheme
import app.passwordstore.ui.list.ListRow
import app.passwordstore.util.autofill.AutofillMatcher
import app.passwordstore.util.autofill.AutofillPreferences
import app.passwordstore.util.coroutines.DispatcherProvider
import app.passwordstore.util.viewmodel.FilterMode
import app.passwordstore.util.viewmodel.ListMode
import app.passwordstore.util.viewmodel.SearchMode
import app.passwordstore.util.viewmodel.SearchableRepositoryViewModel
import com.github.androidpasswordstore.autofillparser.FormOrigin
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import logcat.LogPriority.ERROR
import logcat.logcat

@AndroidEntryPoint
class AutofillFilterView : FragmentActivity() {

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  companion object {

    private const val HEIGHT_PERCENTAGE = 0.9
    private const val WIDTH_PERCENTAGE = 0.75

    private const val EXTRA_FORM_ORIGIN_WEB =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_WEB"
    private const val EXTRA_FORM_ORIGIN_APP =
      "app.passwordstore.autofill.oreo.ui.EXTRA_FORM_ORIGIN_APP"
    private var matchAndDecryptFileRequestCode = 1

    fun makeMatchAndDecryptFileIntentSender(
      context: Context,
      formOrigin: FormOrigin,
    ): IntentSender {
      val intent =
        Intent(context, AutofillFilterView::class.java).apply {
          when (formOrigin) {
            is FormOrigin.Web -> putExtra(EXTRA_FORM_ORIGIN_WEB, formOrigin.identifier)
            is FormOrigin.App -> putExtra(EXTRA_FORM_ORIGIN_APP, formOrigin.identifier)
          }
        }
      return PendingIntent.getActivity(
          context,
          matchAndDecryptFileRequestCode++,
          intent,
          PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        .intentSender
    }
  }

  private lateinit var formOrigin: FormOrigin

  private val model: SearchableRepositoryViewModel by viewModels()

  /** Set by the first tap on a row: a double tap must not start a second decryption. */
  private var picked = false

  private val decryptAction =
    registerForActivityResult(StartActivityForResult()) { result ->
      if (result.resultCode == RESULT_OK) {
        setResult(RESULT_OK, result.data)
      }
      finish()
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The picker decides which entry fills the requesting app: drop touches under an overlay, as
    // the
    // publisher warning does.
    window.decorView.filterTouchesWhenObscured = true
    setFinishOnTouchOutside(true)

    val params = window.attributes
    params.height = (HEIGHT_PERCENTAGE * resources.displayMetrics.heightPixels).toInt()
    params.width = (WIDTH_PERCENTAGE * resources.displayMetrics.widthPixels).toInt()
    window.attributes = params

    if (intent?.hasExtra(AutofillManager.EXTRA_CLIENT_STATE) != true) {
      logcat(ERROR) { "AutofillFilterActivity started without EXTRA_CLIENT_STATE" }
      finish()
      return
    }
    formOrigin =
      when {
        intent?.hasExtra(EXTRA_FORM_ORIGIN_WEB) == true -> {
          FormOrigin.Web(intent!!.getStringExtra(EXTRA_FORM_ORIGIN_WEB)!!)
        }
        intent?.hasExtra(EXTRA_FORM_ORIGIN_APP) == true -> {
          FormOrigin.App(intent!!.getStringExtra(EXTRA_FORM_ORIGIN_APP)!!)
        }
        else -> {
          logcat(ERROR) {
            "AutofillFilterActivity started without EXTRA_FORM_ORIGIN_WEB or EXTRA_FORM_ORIGIN_APP"
          }
          finish()
          return
        }
      }
    setResult(RESULT_CANCELED)

    val isWeb = formOrigin is FormOrigin.Web
    val initialQuery = formOrigin.getPrettyIdentifier(applicationContext, untrusted = false)
    val root = PasswordRepository.getRepositoryDirectory()
    val directoryStructure = AutofillPreferences.directoryStructure(this)
    // ponytail: cold flow, so each return to STARTED walks the store again; fine for a picker open
    // a few seconds. Share it with stateIn in the ViewModel if a large store makes it slow.
    val rows =
      model.searchResult
        .map { result ->
          autofillRows(result.passwordItems.map { it.file }, root, directoryStructure)
        }
        .flowOn(dispatcherProvider.io())
    val origin = formOrigin.getPrettyIdentifier(applicationContext, untrusted = true)
    val matchLabel =
      getString(
        R.string.oreo_autofill_match_with,
        formOrigin.getPrettyIdentifier(applicationContext),
      )

    setContent {
      APSTheme {
        var query by rememberSaveable { mutableStateOf(initialQuery) }
        var strict by rememberSaveable { mutableStateOf(isWeb) }
        var match by rememberSaveable { mutableStateOf(true) }
        var clear by rememberSaveable { mutableStateOf(false) }
        // During the first composition, before rows is collected, so the ViewModel's default root
        // listing (folders included) is never shown, and with the restored query and switch after
        // recreation. The LaunchedEffect repeats it with equal arguments, which emits nothing.
        remember { search(query, strict) }
        LaunchedEffect(query, strict) { search(query, strict) }
        val shown by rows.collectAsStateWithLifecycle<ImmutableList<ListRow>?>(initialValue = null)
        AutofillFilterScreen(
          origin = origin,
          query = query,
          rows = shown,
          showStrict = isWeb,
          strict = strict,
          matchLabel = matchLabel,
          match = match,
          clear = clear,
          onQueryChange = { query = it },
          onStrictChange = { strict = it },
          onMatchChange = { match = it },
          onClearChange = { clear = it },
          onPick = { path -> decryptAndFill(File(path), match, clear) },
        )
      }
    }
  }

  /** The old picker's search: fuzzy or strict domain, recursive, entries only. */
  private fun search(query: String, strict: Boolean) {
    model.search(
      query.trim(),
      filterMode = if (strict) FilterMode.StrictDomain else FilterMode.Fuzzy,
      searchMode = SearchMode.RecursivelyInSubdirectories,
      listMode = ListMode.FilesOnly,
    )
  }

  private fun decryptAndFill(file: File, match: Boolean, clear: Boolean) {
    if (picked) return
    picked = true
    if (clear) AutofillMatcher.clearMatchesFor(applicationContext, formOrigin)
    if (match) AutofillMatcher.addMatchFor(applicationContext, formOrigin, file)
    // intent?.extras? is checked to be non-null in onCreate
    decryptAction.launch(
      AutofillDecryptActivity.makeDecryptFileIntent(file, intent!!.extras!!, this)
    )
  }
}
