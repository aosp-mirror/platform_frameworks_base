/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.desktopmode

import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.windowingModeToString
import android.content.Context
import android.provider.Settings
import android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
import android.view.Display.DEFAULT_DISPLAY
import android.view.IWindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.WindowContainerTransaction
import com.android.internal.protolog.ProtoLog
import com.android.window.flags.Flags
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayController.OnDisplaysChangedListener
import com.android.wm.shell.desktopmode.multidesks.OnDeskRemovedListener
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions

/** Handles display events in desktop mode */
class DesktopDisplayEventHandler(
    private val context: Context,
    shellInit: ShellInit,
    private val transitions: Transitions,
    private val displayController: DisplayController,
    private val rootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer,
    private val windowManager: IWindowManager,
    private val desktopUserRepositories: DesktopUserRepositories,
    private val desktopTasksController: DesktopTasksController,
    private val shellTaskOrganizer: ShellTaskOrganizer,
) : OnDisplaysChangedListener, OnDeskRemovedListener {

    private val desktopRepository: DesktopRepository
        get() = desktopUserRepositories.current

    init {
        shellInit.addInitCallback({ onInit() }, this)
    }

    private fun onInit() {
        displayController.addDisplayWindowListener(this)

        if (Flags.enableMultipleDesktopsBackend()) {
            desktopTasksController.onDeskRemovedListener = this
        }
    }

    override fun onDisplayAdded(displayId: Int) {
        if (displayId != DEFAULT_DISPLAY) {
            refreshDisplayWindowingMode()
        }

        if (!supportsDesks(displayId)) {
            logV("Display #$displayId does not support desks")
            return
        }
        logV("Creating new desk in new display#$displayId")
        // TODO: b/362720497 - when SystemUI crashes with a freeform task open for any reason, the
        //  task is recreated and received in [FreeformTaskListener] before this display callback
        //  is invoked, which results in the repository trying to add the task to a desk before the
        //  desk has been recreated here, which may result in a crash-loop if the repository is
        //  checking that the desk exists before adding a task to it. See b/391984373.
        desktopTasksController.createDesk(displayId)
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (displayId != DEFAULT_DISPLAY) {
            refreshDisplayWindowingMode()
        }

        // TODO: b/362720497 - move desks in closing display to the remaining desk.
    }

    override fun onDeskRemoved(lastDisplayId: Int, deskId: Int) {
        val remainingDesks = desktopRepository.getNumberOfDesks(lastDisplayId)
        if (remainingDesks == 0) {
            logV("All desks removed from display#$lastDisplayId, creating empty desk")
            desktopTasksController.createDesk(lastDisplayId)
        }
    }

    private fun refreshDisplayWindowingMode() {
        if (!Flags.enableDisplayWindowingModeSwitching()) return
        // TODO: b/375319538 - Replace the check with a DisplayManager API once it's available.
        val isExtendedDisplayEnabled =
            0 !=
                Settings.Global.getInt(
                    context.contentResolver,
                    DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS,
                    0,
                )
        if (!isExtendedDisplayEnabled) {
            // No action needed in mirror or projected mode.
            return
        }

        val hasNonDefaultDisplay =
            rootTaskDisplayAreaOrganizer.getDisplayIds().any { displayId ->
                displayId != DEFAULT_DISPLAY
            }
        val targetDisplayWindowingMode =
            if (hasNonDefaultDisplay) {
                WINDOWING_MODE_FREEFORM
            } else {
                // Use the default display windowing mode when no non-default display.
                windowManager.getWindowingMode(DEFAULT_DISPLAY)
            }
        val tdaInfo = rootTaskDisplayAreaOrganizer.getDisplayAreaInfo(DEFAULT_DISPLAY)
        requireNotNull(tdaInfo) { "DisplayAreaInfo of DEFAULT_DISPLAY must be non-null." }
        val currentDisplayWindowingMode = tdaInfo.configuration.windowConfiguration.windowingMode
        if (currentDisplayWindowingMode == targetDisplayWindowingMode) {
            // Already in the target mode.
            return
        }

        logV(
            "As an external display is connected, changing default display's windowing mode from" +
                " ${windowingModeToString(currentDisplayWindowingMode)}" +
                " to ${windowingModeToString(targetDisplayWindowingMode)}"
        )

        val wct = WindowContainerTransaction()
        wct.setWindowingMode(tdaInfo.token, targetDisplayWindowingMode)
        shellTaskOrganizer
            .getRunningTasks(DEFAULT_DISPLAY)
            .filter { it.activityType == ACTIVITY_TYPE_STANDARD }
            .forEach {
                // TODO: b/391965153 - Reconsider the logic under multi-desk window hierarchy
                when (it.windowingMode) {
                    currentDisplayWindowingMode -> {
                        wct.setWindowingMode(it.token, currentDisplayWindowingMode)
                    }
                    targetDisplayWindowingMode -> {
                        wct.setWindowingMode(it.token, WINDOWING_MODE_UNDEFINED)
                    }
                }
            }
        transitions.startTransition(TRANSIT_CHANGE, wct, /* handler= */ null)
    }

    // TODO: b/362720497 - connected/projected display considerations.
    private fun supportsDesks(displayId: Int): Boolean =
        DesktopModeStatus.canEnterDesktopMode(context)

    private fun logV(msg: String, vararg arguments: Any?) {
        ProtoLog.v(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    companion object {
        private const val TAG = "DesktopDisplayEventHandler"
    }
}
