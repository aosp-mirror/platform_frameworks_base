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

package com.android.systemui.screenrecord

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.permission.BaseMediaProjectionPermissionViewBinder
import com.android.systemui.mediaprojection.permission.ENTIRE_SCREEN
import com.android.systemui.mediaprojection.permission.SINGLE_APP
import com.android.systemui.mediaprojection.permission.ScreenShareMode
import com.android.systemui.mediaprojection.permission.ScreenShareOption
import com.android.systemui.res.R

class ScreenRecordPermissionViewBinder(
    hostUid: Int,
    mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @ScreenShareMode defaultSelectedMode: Int,
    displayManager: DisplayManager,
    private val dialog: AlertDialog,
) :
    BaseMediaProjectionPermissionViewBinder(
        createOptionList(displayManager),
        appName = null,
        hostUid = hostUid,
        mediaProjectionMetricsLogger,
        defaultSelectedMode,
        dialog,
    ) {
    private lateinit var tapsView: View

    override fun bind() {
        super.bind()
        initRecordOptionsView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initRecordOptionsView() {
        tapsView = dialog.requireViewById(R.id.show_taps)
        updateTapsViewVisibility()
    }

    override fun onItemSelected(pos: Int) {
        super.onItemSelected(pos)
        updateTapsViewVisibility()
    }

    private fun updateTapsViewVisibility() {
        tapsView.visibility = if (selectedScreenShareOption.mode == SINGLE_APP) GONE else VISIBLE
    }

    companion object {
        private val RECORDABLE_DISPLAY_TYPES =
            intArrayOf(
                Display.TYPE_OVERLAY,
                Display.TYPE_EXTERNAL,
                Display.TYPE_INTERNAL,
                Display.TYPE_WIFI,
            )

        private val filterDeviceTypeFlag: Boolean =
            com.android.media.projection.flags.Flags
                .mediaProjectionConnectedDisplayNoVirtualDevice()

        fun createOptionList(displayManager: DisplayManager): List<ScreenShareOption> {
            if (!com.android.media.projection.flags.Flags.mediaProjectionConnectedDisplay()) {
                return listOf(
                    ScreenShareOption(
                        SINGLE_APP,
                        R.string.screenrecord_permission_dialog_option_text_single_app,
                        R.string.screenrecord_permission_dialog_warning_single_app,
                        startButtonText =
                            R.string
                                .media_projection_entry_generic_permission_dialog_continue_single_app,
                    ),
                    ScreenShareOption(
                        ENTIRE_SCREEN,
                        R.string.screenrecord_permission_dialog_option_text_entire_screen,
                        R.string.screenrecord_permission_dialog_warning_entire_screen,
                        startButtonText =
                            R.string.screenrecord_permission_dialog_continue_entire_screen,
                        displayId = Display.DEFAULT_DISPLAY,
                        displayName = Build.MODEL,
                    ),
                )
            }

            return listOf(
                ScreenShareOption(
                    SINGLE_APP,
                    R.string.screenrecord_permission_dialog_option_text_single_app,
                    R.string.screenrecord_permission_dialog_warning_single_app,
                    startButtonText =
                        R.string
                            .media_projection_entry_generic_permission_dialog_continue_single_app,
                ),
                ScreenShareOption(
                    ENTIRE_SCREEN,
                    R.string.screenrecord_permission_dialog_option_text_entire_screen_for_display,
                    R.string.screenrecord_permission_dialog_warning_entire_screen,
                    startButtonText =
                        R.string.screenrecord_permission_dialog_continue_entire_screen,
                    displayId = Display.DEFAULT_DISPLAY,
                    displayName = Build.MODEL,
                ),
            ) +
                displayManager.displays
                    .filter {
                        it.displayId != Display.DEFAULT_DISPLAY &&
                            (!filterDeviceTypeFlag || it.type in RECORDABLE_DISPLAY_TYPES)
                    }
                    .map {
                        ScreenShareOption(
                            ENTIRE_SCREEN,
                            R.string
                                .screenrecord_permission_dialog_option_text_entire_screen_for_display,
                            warningText =
                                R.string
                                    .media_projection_entry_app_permission_dialog_warning_entire_screen,
                            startButtonText =
                                R.string
                                    .media_projection_entry_app_permission_dialog_continue_entire_screen,
                            displayId = it.displayId,
                            displayName = it.name,
                        )
                    }
        }
    }
}
