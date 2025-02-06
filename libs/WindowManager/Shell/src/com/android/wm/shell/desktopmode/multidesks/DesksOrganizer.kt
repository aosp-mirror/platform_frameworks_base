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

import android.app.ActivityManager
import android.window.TransitionInfo
import android.window.WindowContainerTransaction

/** An organizer of desk containers in which to host child desktop windows. */
interface DesksOrganizer {
    /** Creates a new desk container in the given display. */
    fun createDesk(displayId: Int, callback: OnCreateCallback)

    /** Activates the given desk, making it visible in its display. */
    fun activateDesk(wct: WindowContainerTransaction, deskId: Int)

    /** Removes the given desk and its desktop windows. */
    fun removeDesk(wct: WindowContainerTransaction, deskId: Int)

    /** Moves the given task to the given desk. */
    fun moveTaskToDesk(
        wct: WindowContainerTransaction,
        deskId: Int,
        task: ActivityManager.RunningTaskInfo,
    )

    /**
     * Returns the desk id in which the task in the given change is located at the end of a
     * transition, if any.
     */
    fun getDeskAtEnd(change: TransitionInfo.Change): Int?

    /** Whether the desk is activate according to the given change at the end of a transition. */
    fun isDeskActiveAtEnd(change: TransitionInfo.Change, deskId: Int): Boolean

    /** A callback that is invoked when the desk container is created. */
    fun interface OnCreateCallback {
        /** Calls back when the [deskId] has been created. */
        fun onCreated(deskId: Int)
    }
}
