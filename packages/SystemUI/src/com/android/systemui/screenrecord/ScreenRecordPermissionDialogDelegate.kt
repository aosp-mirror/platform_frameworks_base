/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.UserHandle
import android.view.View
import androidx.annotation.StyleRes
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.permission.BaseMediaProjectionPermissionDialogDelegate
import com.android.systemui.mediaprojection.permission.BaseMediaProjectionPermissionViewBinder
import com.android.systemui.mediaprojection.permission.SINGLE_APP
import com.android.systemui.mediaprojection.permission.ScreenShareMode
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/** Dialog to select screen recording options */
class ScreenRecordPermissionDialogDelegate(
    private val hostUserHandle: UserHandle,
    private val hostUid: Int,
    private val controller: RecordingController,
    private val activityStarter: ActivityStarter,
    private val userContextProvider: UserContextProvider,
    private val onStartRecordingClicked: Runnable?,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @ScreenShareMode defaultSelectedMode: Int,
    @StyleRes private val theme: Int,
    private val context: Context,
    private val displayManager: DisplayManager,
) :
    BaseMediaProjectionPermissionDialogDelegate<SystemUIDialog>(
        ScreenRecordPermissionViewBinder.createOptionList(displayManager),
        appName = null,
        hostUid = hostUid,
        mediaProjectionMetricsLogger,
        R.drawable.ic_screenrecord,
        R.color.screenrecord_icon_color,
        defaultSelectedMode,
    ),
    SystemUIDialog.Delegate {
    @AssistedInject
    constructor(
        @Assisted hostUserHandle: UserHandle,
        @Assisted hostUid: Int,
        @Assisted controller: RecordingController,
        activityStarter: ActivityStarter,
        userContextProvider: UserContextProvider,
        @Assisted onStartRecordingClicked: Runnable?,
        mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
        systemUIDialogFactory: SystemUIDialog.Factory,
        @Application context: Context,
        displayManager: DisplayManager,
    ) : this(
        hostUserHandle,
        hostUid,
        controller,
        activityStarter,
        userContextProvider,
        onStartRecordingClicked,
        mediaProjectionMetricsLogger,
        systemUIDialogFactory,
        defaultSelectedMode = SINGLE_APP,
        theme = SystemUIDialog.DEFAULT_THEME,
        context,
        displayManager,
    )

    @AssistedFactory
    interface Factory {
        fun create(
            recordingController: RecordingController,
            hostUserHandle: UserHandle,
            hostUid: Int,
            onStartRecordingClicked: Runnable?,
        ): ScreenRecordPermissionDialogDelegate
    }

    override fun createViewBinder(): BaseMediaProjectionPermissionViewBinder {
        return ScreenRecordPermissionViewBinder(
            hostUserHandle,
            hostUid,
            mediaProjectionMetricsLogger,
            defaultSelectedMode,
            displayManager,
            dialog,
            controller,
            activityStarter,
            userContextProvider,
            onStartRecordingClicked,
        )
    }

    override fun createDialog(): SystemUIDialog {
        return systemUIDialogFactory.create(this, context, theme)
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        super<BaseMediaProjectionPermissionDialogDelegate>.onCreate(dialog, savedInstanceState)
        setDialogTitle(R.string.screenrecord_permission_dialog_title)
        dialog.setTitle(R.string.screenrecord_title)
        setStartButtonOnClickListener { v: View? ->
            val screenRecordViewBinder: ScreenRecordPermissionViewBinder? =
                viewBinder as ScreenRecordPermissionViewBinder?
            screenRecordViewBinder?.startButtonOnClicked()
            dialog.dismiss()
        }
        setCancelButtonOnClickListener { dialog.dismiss() }
    }
}
