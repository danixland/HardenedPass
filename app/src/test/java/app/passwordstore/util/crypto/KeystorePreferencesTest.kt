/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.crypto

import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.ivanshafran.sharedpreferencesmock.SPMockBuilder
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeystorePreferencesTest {

  private lateinit var backing: SharedPreferences
  private lateinit var prefs: SharedPreferences
  private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

  @BeforeTest
  fun setup() {
    backing = SPMockBuilder().createSharedPreferences()
    prefs = KeystorePreferences(backing) { key }
  }

  @Test
  fun roundTripsStringsAndInts() {
    prefs.edit {
      putString("password", "hunter2")
      putInt("port", 8080)
    }
    assertEquals("hunter2", prefs.getString("password", null))
    assertEquals(8080, prefs.getInt("port", -1))
  }

  @Test
  fun returnsDefaultsWhenAbsent() {
    assertNull(prefs.getString("password", null))
    assertEquals(-1, prefs.getInt("port", -1))
    assertFalse(prefs.contains("password"))
  }

  @Test
  fun storesNoPlaintext() {
    prefs.edit { putString("password", "hunter2") }
    val stored = backing.getString("password", null)!!
    assertFalse(stored.contains("hunter2"))
    prefs.edit { putString("other", "hunter2") }
    assertNotEquals(stored, backing.getString("other", null), "IVs must be random")
  }

  @Test
  fun rejectsValueMovedToAnotherName() {
    prefs.edit { putString("password", "hunter2") }
    backing.edit { putString("username", backing.getString("password", null)) }
    assertNull(prefs.getString("username", null))
  }

  @Test
  fun rejectsTamperedValue() {
    prefs.edit { putString("password", "hunter2") }
    val bytes = Base64.getDecoder().decode(backing.getString("password", null))
    bytes[bytes.size - 1] = (bytes.last().toInt() xor 1).toByte()
    backing.edit { putString("password", Base64.getEncoder().encodeToString(bytes)) }
    assertNull(prefs.getString("password", null))
  }

  @Test
  fun removeAndClear() {
    prefs.edit {
      putString("a", "1")
      putString("b", "2")
    }
    prefs.edit { remove("a") }
    assertFalse(prefs.contains("a"))
    assertTrue(prefs.contains("b"))
    prefs.edit { clear() }
    assertFalse(prefs.contains("b"))
  }
}
