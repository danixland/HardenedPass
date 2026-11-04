/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.editor

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.SecureFlagPolicy
import app.passwordstore.R
import app.passwordstore.passgen.random.PasswordGenerator
import app.passwordstore.ui.compose.theme.SpacingLarge
import app.passwordstore.ui.compose.theme.SpacingMedium
import app.passwordstore.ui.compose.theme.SpacingSmall
import com.github.michaelbull.result.Result

private const val RANDOM_TAB = 0
private const val DICEWARE_TAB = 1

/**
 * The password generator as a bottom sheet: a random tab and a diceware tab with the old dialogs'
 * options, starting from [random] and [diceware]. [classicFirst] opens the random tab (the
 * "Password generator" setting). Every change generates again; [onUse] takes the password shown.
 * The password lives in [remember] only, and the sheet's window is FLAG_SECURE.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeneratorSheet(
  classicFirst: Boolean,
  random: RandomSpec,
  diceware: DicewareSpec,
  onRandom: suspend (RandomSpec) -> Result<String, Int>,
  onDiceware: suspend (DicewareSpec) -> String,
  onUse: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var tab by remember { mutableIntStateOf(if (classicFirst) RANDOM_TAB else DICEWARE_TAB) }
  var randomSpec by remember { mutableStateOf(random) }
  var dicewareSpec by remember { mutableStateOf(diceware) }
  var length by remember { mutableStateOf(random.length.toString()) }
  var words by remember { mutableStateOf(diceware.words.toString()) }
  var separator by remember { mutableStateOf(diceware.separator.toString()) }
  var rolls by remember { mutableIntStateOf(0) }
  var password by remember { mutableStateOf("") }
  var problem by remember { mutableStateOf<Int?>(null) }
  LaunchedEffect(tab, randomSpec, dicewareSpec, rolls) {
    if (tab == RANDOM_TAB) {
      val result = onRandom(randomSpec)
      password = if (result.isOk) result.value else ""
      problem = if (result.isOk) null else result.error
    } else {
      password = onDiceware(dicewareSpec)
      problem = null
    }
  }
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    modifier = modifier,
    properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
  ) {
    PrimaryTabRow(selectedTabIndex = tab) {
      Tab(
        selected = tab == RANDOM_TAB,
        onClick = { tab = RANDOM_TAB },
        text = { Text(stringResource(R.string.generator_random)) },
      )
      Tab(
        selected = tab == DICEWARE_TAB,
        onClick = { tab = DICEWARE_TAB },
        text = { Text(stringResource(R.string.generator_diceware)) },
      )
    }
    Column(
      Modifier.verticalScroll(rememberScrollState()).padding(SpacingLarge),
      verticalArrangement = Arrangement.spacedBy(SpacingSmall),
    ) {
      val shown = problem
      Text(
        if (shown == null) password else stringResource(shown),
        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        color =
          if (shown == null) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.error,
      )
      if (tab == RANDOM_TAB) {
        CheckRow(R.string.pwgen_numerals, randomSpec.numerals) {
          randomSpec = randomSpec.copy(numerals = it)
        }
        CheckRow(R.string.pwgen_symbols, randomSpec.symbols) {
          randomSpec = randomSpec.copy(symbols = it)
        }
        CheckRow(R.string.pwgen_uppercase, randomSpec.uppercase) {
          randomSpec = randomSpec.copy(uppercase = it)
        }
        CheckRow(R.string.pwgen_lowercase, randomSpec.lowercase) {
          randomSpec = randomSpec.copy(lowercase = it)
        }
        CheckRow(R.string.pwgen_pronounceable, randomSpec.pronounceable) {
          randomSpec = randomSpec.copy(pronounceable = it)
        }
        CheckRow(R.string.pwgen_ambiguous, randomSpec.ambiguous) {
          randomSpec = randomSpec.copy(ambiguous = it)
        }
        OutlinedTextField(
          value = length,
          onValueChange = { text ->
            // ponytail: at most 999 characters; the generator has no upper bound of its own.
            val digits = text.filter(Char::isDigit).take(3)
            length = digits
            randomSpec =
              randomSpec.copy(length = digits.toIntOrNull() ?: PasswordGenerator.DEFAULT_LENGTH)
          },
          label = { Text(stringResource(R.string.pwgen_length)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      } else {
        OutlinedTextField(
          value = words,
          onValueChange = { text ->
            // ponytail: at most 99 words.
            val digits = text.filter(Char::isDigit).take(2)
            words = digits
            dicewareSpec = dicewareSpec.copy(words = digits.toIntOrNull()?.takeIf { it > 0 } ?: 5)
          },
          label = { Text(stringResource(R.string.pwgen_length)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
          value = separator,
          onValueChange = { text ->
            separator = text.take(1)
            dicewareSpec = dicewareSpec.copy(separator = separator.firstOrNull() ?: '-')
          },
          label = { Text(stringResource(R.string.pwgen_separator)) },
          singleLine = true,
        )
      }
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingMedium, Alignment.End),
      ) {
        TextButton(onClick = { rolls++ }) { Text(stringResource(R.string.pwgen_generate)) }
        Button(onClick = { onUse(password) }, enabled = password.isNotEmpty() && problem == null) {
          Text(stringResource(R.string.generator_use))
        }
      }
    }
  }
}

@Composable
private fun CheckRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    Modifier.fillMaxWidth()
      .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Checkbox(checked = checked, onCheckedChange = null)
    Text(stringResource(label), Modifier.padding(start = SpacingMedium))
  }
}
