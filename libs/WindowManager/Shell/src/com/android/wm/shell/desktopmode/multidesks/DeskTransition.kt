/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.wm.shell.desktopmode.multidesks

import android.os.IBinder

/** Represents shell-started transitions involving desks. */
sealed class DeskTransition {
    /** The transition token. */
    abstract val token: IBinder

    /** A transition to remove a desk and its tasks from a display. */
    data class RemoveDesk(
        override val token: IBinder,
        val displayId: Int,
        val deskId: Int,
        val tasks: Set<Int>,
        val onDeskRemovedListener: OnDeskRemovedListener?,
    ) : DeskTransition()

    /** A transition to activate a desk in its display. */
    data class ActivateDesk(override val token: IBinder, val displayId: Int, val deskId: Int) :
        DeskTransition()

    /** A transition to activate a desk by moving an outside task to it. */
    data class ActiveDeskWithTask(
        override val token: IBinder,
        val displayId: Int,
        val deskId: Int,
        val enterTaskId: Int,
    ) : DeskTransition()
}
