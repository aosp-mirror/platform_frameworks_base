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
import android.view.WindowManager.TRANSIT_CLOSE
import android.window.TransitionInfo
import com.android.window.flags.Flags
import com.android.wm.shell.desktopmode.DesktopUserRepositories

/**
 * Observer of desk-related transitions, such as adding, removing or activating a whole desk. It
 * tracks pending transitions and updates repository state once they finish.
 */
class DesksTransitionObserver(private val desktopUserRepositories: DesktopUserRepositories) {
    private val deskTransitions = mutableMapOf<IBinder, DeskTransition>()

    /** Adds a pending desk transition to be tracked. */
    fun addPendingTransition(transition: DeskTransition) {
        if (!Flags.enableMultipleDesktopsBackend()) return
        deskTransitions[transition.token] = transition
    }

    /**
     * Called when any transition is ready, which may include transitions not tracked by this
     * observer.
     */
    fun onTransitionReady(transition: IBinder, info: TransitionInfo) {
        if (!Flags.enableMultipleDesktopsBackend()) return
        val deskTransition = deskTransitions.remove(transition) ?: return
        val desktopRepository = desktopUserRepositories.current
        when (deskTransition) {
            is DeskTransition.RemoveDesk -> {
                check(info.type == TRANSIT_CLOSE) { "Expected close transition for desk removal" }
                // TODO: b/362720497 - consider verifying the desk was actually removed through the
                //  DesksOrganizer. The transition info won't have changes if the desk was not
                //  visible, such as when dismissing from Overview.
                val deskId = deskTransition.deskId
                val displayId = deskTransition.displayId
                desktopRepository.removeDesk(deskTransition.deskId)
                deskTransition.onDeskRemovedListener?.onDeskRemoved(displayId, deskId)
            }
        }
    }
}
