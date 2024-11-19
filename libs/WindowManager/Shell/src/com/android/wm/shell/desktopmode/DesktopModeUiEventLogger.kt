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

import com.android.internal.logging.InstanceId
import com.android.internal.logging.InstanceIdSequence
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.internal.protolog.ProtoLog
import com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE

/** Log Aster UIEvents for desktop windowing mode. */
class DesktopModeUiEventLogger(
    private val uiEventLogger: UiEventLogger,
) {
    private val instanceIdSequence = InstanceIdSequence(Integer.MAX_VALUE)

    /**
     * Logs an event for a CUI, on a particular package.
     *
     * @param uid The user id associated with the package the user is interacting with
     * @param packageName The name of the package the user is interacting with
     * @param event The event type to generate
     */
    fun log(uid: Int, packageName: String, event: DesktopUiEventEnum) {
        if (packageName.isEmpty() || uid < 0) {
            logD("Skip logging since package name is empty or bad uid")
            return
        }
        uiEventLogger.log(event, uid, packageName)
    }

    /** Retrieves a new instance id for a new interaction. */
    fun getNewInstanceId(): InstanceId = instanceIdSequence.newInstanceId()

    /**
     * Logs an event as part of a particular CUI, on a particular package.
     *
     * @param instanceId The id identifying an interaction, potentially taking place across multiple
     *   surfaces. There should be a new id generated for each distinct CUI.
     * @param uid The user id associated with the package the user is interacting with
     * @param packageName The name of the package the user is interacting with
     * @param event The event type to generate
     */
    fun logWithInstanceId(
        instanceId: InstanceId,
        uid: Int,
        packageName: String,
        event: DesktopUiEventEnum
    ) {
        if (packageName.isEmpty() || uid < 0) {
            logD("Skip logging since package name is empty or bad uid")
            return
        }
        uiEventLogger.logWithInstanceId(event, uid, packageName, instanceId)
    }

    private fun logD(msg: String, vararg arguments: Any?) {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "%s: $msg", TAG, *arguments)
    }

    /** Enums for logging desktop windowing mode UiEvents. */
    enum class DesktopUiEventEnum(private val mId: Int) : UiEventEnum {

        @UiEvent(doc = "Resize the window in desktop windowing mode by dragging the edge")
        DESKTOP_WINDOW_EDGE_DRAG_RESIZE(1721),
        @UiEvent(doc = "Resize the window in desktop windowing mode by dragging the corner")
        DESKTOP_WINDOW_CORNER_DRAG_RESIZE(1722),
        @UiEvent(doc = "Tap on the window header maximize button in desktop windowing mode")
        DESKTOP_WINDOW_MAXIMIZE_BUTTON_TAP(1723),
        @UiEvent(doc = "Double tap on window header to maximize it in desktop windowing mode")
        DESKTOP_WINDOW_HEADER_DOUBLE_TAP_TO_MAXIMIZE(1724);

        override fun getId(): Int = mId
    }

    companion object {
        private const val TAG = "DesktopModeUiEventLogger"
    }
}
