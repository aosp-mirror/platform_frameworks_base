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

import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.content.Context
import android.provider.Settings
import android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS
import android.view.Display.DEFAULT_DISPLAY
import android.view.IWindowManager
import android.view.WindowManager.TRANSIT_CHANGE
import android.window.WindowContainerTransaction
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayController.OnDisplaysChangedListener
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
) : OnDisplaysChangedListener {

    init {
        shellInit.addInitCallback({ onInit() }, this)
    }

    private fun onInit() {
        displayController.addDisplayWindowListener(this)
    }

    override fun onDisplayAdded(displayId: Int) {
        if (displayId == DEFAULT_DISPLAY) {
            return
        }
        refreshDisplayWindowingMode()
    }

    override fun onDisplayRemoved(displayId: Int) {
        if (displayId == DEFAULT_DISPLAY) {
            return
        }
        refreshDisplayWindowingMode()
    }

    private fun refreshDisplayWindowingMode() {
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
        if (tdaInfo.configuration.windowConfiguration.windowingMode == targetDisplayWindowingMode) {
            // Already in the target mode.
            return
        }

        val wct = WindowContainerTransaction()
        wct.setWindowingMode(tdaInfo.token, targetDisplayWindowingMode)
        transitions.startTransition(TRANSIT_CHANGE, wct, /* handler= */ null)
    }
}
