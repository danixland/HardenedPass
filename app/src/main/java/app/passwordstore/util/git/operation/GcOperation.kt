/*
 * Copyright © 2014-2024 The Android Password Store Authors. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-only
 */

package app.passwordstore.util.git.operation

import androidx.fragment.app.FragmentActivity
import org.eclipse.jgit.api.GitCommand
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC

/**
 * Run an aggressive garbage collection job on the repository, expiring every loose object to
 * achieve the best compression.
 */
class GcOperation(callingActivity: FragmentActivity) : GitOperation(callingActivity) {

  override val requiresAuth: Boolean = false
  override val commands: Array<GitCommand<out Any>> =
    arrayOf(
      object : GitCommand<Unit>(repository) {
        // ponytail: JGit's GC.gc() takes a PID lock that calls java.lang.management, which
        // Android lacks, so run its steps directly. The lock only guards against concurrent gc
        // processes on one repository, which cannot happen here. No aggressive packing either:
        // too slow on a phone for a password store's size.
        override fun call() {
          GC(repository as FileRepository).run {
            setExpire(null)
            packRefs()
            repack()
            prune(emptySet())
          }
        }
      }
    )
}
