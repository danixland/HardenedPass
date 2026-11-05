/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.prompts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.passwordstore.R
import app.passwordstore.ui.compose.R as composeR
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The button that answered a [Question]. Dismissing the dialog answers [Cancel]. */
enum class Choice {
  Confirm,
  Other,
  Cancel,
}

/** What the user entered in a [CredentialRequest]. */
class CredentialAnswer(val text: String, val remember: Boolean)

/** A dialog waiting for its answer; [cancelled] is the answer to dismissing it. */
sealed class Prompt<T>(private val cancelled: T, private val deliver: (T) -> Unit) {
  internal fun answer(value: T) = deliver(value)

  internal fun cancel() = deliver(cancelled)
}

class Question(
  val title: String,
  val message: String,
  val confirm: String,
  val cancel: String?,
  val other: String?,
  deliver: (Choice) -> Unit,
) : Prompt<Choice>(Choice.Cancel, deliver)

class CredentialRequest(
  val message: String,
  val hint: String,
  val remember: String,
  val error: String?,
  deliver: (CredentialAnswer?) -> Unit,
) : Prompt<CredentialAnswer?>(null, deliver)

/** A snackbar; each one is a distinct object, so showing the same text again shows it again. */
class Snack(val message: String, val length: SnackbarDuration)

/**
 * The dialogs and the snackbar of one activity, for code with no composable of its own (git
 * operations and store actions), drawn by [PromptHost]. Reach it through [prompts].
 */
class Prompts : ViewModel() {

  private val queue = mutableStateListOf<Prompt<*>>()

  /** Set once the activity is gone for good: nothing draws a new dialog any more. */
  private var closed = false

  /** The dialog on screen. The others wait for it to be answered. */
  val current: Prompt<*>?
    get() = queue.firstOrNull()

  var snack by mutableStateOf<Snack?>(null)
    private set

  /** Shows a dialog and suspends until it is answered. Cancelling the caller closes it. */
  suspend fun ask(
    title: String,
    message: String,
    confirm: String,
    cancel: String? = null,
    other: String? = null,
  ): Choice = suspendCancellableCoroutine { cont ->
    val question = Question(title, message, confirm, cancel, other) { cont.resume(it) }
    queue += question
    cont.invokeOnCancellation { queue.remove(question) }
  }

  /**
   * Asks for a credential. Not suspending: the SSH thread waits on [deliver], and no cancellation
   * reaches it, so this dialog outlives a recreated activity and is drawn again by the new one.
   */
  fun askCredential(
    message: String,
    hint: String,
    remember: String,
    error: String?,
    deliver: (CredentialAnswer?) -> Unit,
  ) {
    if (closed) {
      deliver(null)
      return
    }
    queue += CredentialRequest(message, hint, remember, error, deliver)
  }

  /** Answers [prompt], once: a double tap must not resume its caller twice. */
  fun <T> answer(prompt: Prompt<T>, value: T) {
    if (queue.remove(prompt)) prompt.answer(value)
  }

  fun cancel(prompt: Prompt<*>) {
    if (queue.remove(prompt)) prompt.cancel()
  }

  fun cancelAll() {
    queue.toList().forEach(::cancel)
  }

  /** Shows [message] in place of any current snackbar, as the View snackbar did. */
  fun snackbar(message: String, length: SnackbarDuration = SnackbarDuration.Short): Snack =
    Snack(message, length).also { snack = it }

  /** Hides [shown], unless another snackbar replaced it. */
  fun dismiss(shown: Snack) {
    if (snack === shown) snack = null
  }

  internal fun close() {
    closed = true
    cancelAll()
  }

  override fun onCleared() = close()
}

/** The [Prompts] of this activity; it survives recreation. */
val FragmentActivity.prompts: Prompts
  get() = ViewModelProvider(this)[Prompts::class.java]

/** Draws the [Prompts] of [activity]. Place it after the activity's content, inside `APSTheme`. */
@Composable
fun PromptHost(activity: FragmentActivity, modifier: Modifier = Modifier) {
  val prompts = remember(activity) { activity.prompts }
  val snackbars = remember { SnackbarHostState() }
  val snack = prompts.snack
  // A new snack cancels this effect, which takes the old one off the screen.
  LaunchedEffect(snack) {
    if (snack != null) {
      snackbars.showSnackbar(snack.message, duration = snack.length)
      prompts.dismiss(snack)
    }
  }
  Box(modifier.fillMaxSize().navigationBarsPadding().imePadding(), Alignment.BottomCenter) {
    SnackbarHost(snackbars)
  }
  val prompt = prompts.current
  // Keyed: a retry is a new request and starts with an empty field.
  key(prompt) {
    when (prompt) {
      is Question -> QuestionDialog(prompt, prompts)
      is CredentialRequest -> CredentialDialog(prompt, prompts)
      null -> {}
    }
  }
}

@Composable
private fun QuestionDialog(question: Question, prompts: Prompts) {
  AlertDialog(
    onDismissRequest = { prompts.cancel(question) },
    title = { Text(question.title) },
    text = { Text(question.message) },
    confirmButton = {
      TextButton(onClick = { prompts.answer(question, Choice.Confirm) }) { Text(question.confirm) }
    },
    dismissButton = {
      // No Row: the dialog's button area wraps its children when they do not fit.
      question.cancel?.let {
        TextButton(onClick = { prompts.answer(question, Choice.Cancel) }) { Text(it) }
      }
      question.other?.let {
        TextButton(onClick = { prompts.answer(question, Choice.Other) }) { Text(it) }
      }
    },
  )
}

/** The credential lives only in `remember` and the dialog's window is FLAG_SECURE. */
@Composable
private fun CredentialDialog(request: CredentialRequest, prompts: Prompts) {
  // remember, never rememberSaveable: the credential must not reach saved state.
  var text by remember { mutableStateOf("") }
  var keep by remember { mutableStateOf(true) }
  var revealed by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf(request.error) }
  val focus = remember { FocusRequester() }
  fun submit() = prompts.answer(request, CredentialAnswer(text, keep))
  AlertDialog(
    onDismissRequest = { prompts.cancel(request) },
    properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
    title = { Text(stringResource(R.string.passphrase_dialog_title)) },
    text = {
      Column {
        Text(request.message)
        OutlinedTextField(
          value = text,
          onValueChange = {
            text = it
            error = null
          },
          modifier = Modifier.focusRequester(focus),
          label = { Text(request.hint) },
          supportingText = error?.let { { Text(it) } },
          isError = error != null,
          trailingIcon = {
            IconButton(onClick = { revealed = !revealed }) {
              Icon(
                painterResource(
                  if (revealed) composeR.drawable.baseline_visibility_off_24
                  else composeR.drawable.baseline_visibility_24
                ),
                contentDescription =
                  stringResource(
                    if (revealed) R.string.entry_hide_password else R.string.entry_show_password
                  ),
              )
            }
          },
          visualTransformation =
            if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
          singleLine = true,
          keyboardOptions =
            KeyboardOptions(
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Done,
            ),
          keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        Row(
          Modifier.toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it }),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Checkbox(checked = keep, onCheckedChange = null)
          Text(request.remember)
        }
      }
      LaunchedEffect(Unit) { focus.requestFocus() }
    },
    confirmButton = { TextButton(onClick = ::submit) { Text(stringResource(R.string.dialog_ok)) } },
    dismissButton = {
      TextButton(onClick = { prompts.cancel(request) }) {
        Text(stringResource(R.string.dialog_cancel))
      }
    },
  )
}
