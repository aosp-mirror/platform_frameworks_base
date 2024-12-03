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

package com.android.systemui.mediaprojection.permission

import android.app.AlertDialog
import android.view.View
import android.widget.TextView
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.res.R

open class BaseMediaProjectionPermissionViewBinder(
    private val screenShareOptions: List<ScreenShareOption>,
    private val appName: String?,
    private val hostUid: Int,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    @ScreenShareMode val defaultSelectedMode: Int = screenShareOptions.first().mode,
    private val dialog: AlertDialog,
) {
    private lateinit var warning: TextView
    private lateinit var startButton: TextView
    var selectedScreenShareOption: ScreenShareOption =
        screenShareOptions.first { it.mode == defaultSelectedMode }
    private var shouldLogCancel: Boolean = true

    fun unbind() {
        // unbind can be called multiple times and we only want to log once.
        if (shouldLogCancel) {
            mediaProjectionMetricsLogger.notifyProjectionRequestCancelled(hostUid)
            shouldLogCancel = false
        }
    }

    open fun bind() {
        warning = dialog.requireViewById(R.id.text_warning)
        startButton = dialog.requireViewById(android.R.id.button1)
        initScreenShareOptions()
    }

    private fun initScreenShareOptions() {
        selectedScreenShareOption = screenShareOptions.first { it.mode == defaultSelectedMode }
        setOptionSpecificFields()
    }

    /** Sets fields on the dialog that change based on which option is selected. */
    private fun setOptionSpecificFields() {
        warning.text = warningText
        startButton.text = startButtonText
    }

    open fun onItemSelected(pos: Int) {
        selectedScreenShareOption = screenShareOptions[pos]
        setOptionSpecificFields()
    }

    private val warningText: String
        get() = dialog.context.getString(selectedScreenShareOption.warningText, appName)

    private val startButtonText: String
        get() = dialog.context.getString(selectedScreenShareOption.startButtonText)

    fun setStartButtonOnClickListener(listener: View.OnClickListener?) {
        startButton.setOnClickListener { view ->
            shouldLogCancel = false
            listener?.onClick(view)
        }
    }
}
