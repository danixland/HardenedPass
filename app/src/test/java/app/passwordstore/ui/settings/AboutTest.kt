/*
 * Copyright (C) 2026 Danilo M. <danix@danix.xyz>
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class AboutTest {

  @Test
  fun versionLineWithoutCommit() {
    assertEquals("0.3.0 (300)", versionLine("0.3.0", 300, ""))
  }

  @Test
  fun versionLineWithCommit() {
    assertEquals("0.3.0-debug (300) · daa7ee01", versionLine("0.3.0-debug", 300, "daa7ee01"))
  }

  @Test
  fun changelogUrlPointsAtTheReleaseTag() {
    assertEquals(
      "https://github.com/danixland/HardenedPass/blob/v0.3.0/CHANGELOG.md",
      changelogUrl("0.3.0-debug"),
    )
  }

  @Test
  fun fingerprintIsColonSeparatedUppercaseSha256() {
    assertEquals(
      "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:" +
        "B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
      certificateFingerprint("abc".encodeToByteArray()),
    )
  }
}
