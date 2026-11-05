/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.prompts

import androidx.compose.material3.SnackbarDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class PromptsTest {

  private fun Prompts.question() = current as Question

  @Test
  fun answerResumesAsk() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    prompts.answer(prompts.question(), Choice.Other)
    assertEquals(Choice.Other, asked.await())
    assertNull(prompts.current)
  }

  @Test
  fun dismissingAnswersCancel() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    prompts.cancel(prompts.question())
    assertEquals(Choice.Cancel, asked.await())
  }

  @Test
  fun secondAnswerIsIgnored() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    val question = prompts.question()
    prompts.answer(question, Choice.Confirm)
    prompts.answer(question, Choice.Cancel)
    assertEquals(Choice.Confirm, asked.await())
  }

  @Test
  fun cancelledAskRemovesItsDialog() = runBlocking {
    val prompts = Prompts()
    val asked = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("t", "m", "ok") }
    asked.cancel()
    assertNull(prompts.current)
  }

  @Test
  fun promptsShowOneAtATimeInOrder() =
    runBlocking<Unit> {
      val prompts = Prompts()
      val first = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("1", "m", "ok") }
      val second = async(start = CoroutineStart.UNDISPATCHED) { prompts.ask("2", "m", "ok") }
      assertEquals("1", prompts.question().title)
      prompts.answer(prompts.question(), Choice.Confirm)
      assertEquals("2", prompts.question().title)
      prompts.answer(prompts.question(), Choice.Confirm)
      first.await()
      second.await()
    }

  @Test
  fun cancelAllAnswersAPendingCredentialWithNull() {
    val prompts = Prompts()
    val answers = mutableListOf<CredentialAnswer?>(CredentialAnswer("unset", false))
    prompts.askCredential("m", "h", "r", null) { answers[0] = it }
    prompts.cancelAll()
    assertNull(answers[0])
    assertNull(prompts.current)
  }

  @Test
  fun askCredentialAfterCloseAnswersNullAtOnce() {
    val prompts = Prompts()
    prompts.close()
    val answers = mutableListOf<CredentialAnswer?>(CredentialAnswer("unset", false))
    prompts.askCredential("m", "h", "r", null) { answers[0] = it }
    assertNull(answers[0])
    assertNull(prompts.current)
  }

  @Test
  fun newSnackbarReplacesTheCurrentOne() {
    val prompts = Prompts()
    val first = prompts.snackbar("one", SnackbarDuration.Indefinite)
    val second = prompts.snackbar("two", SnackbarDuration.Short)
    assertSame(second, prompts.snack)
    // The finished operation dismisses its own snackbar only.
    prompts.dismiss(first)
    assertSame(second, prompts.snack)
    prompts.dismiss(second)
    assertNull(prompts.snack)
  }
}
