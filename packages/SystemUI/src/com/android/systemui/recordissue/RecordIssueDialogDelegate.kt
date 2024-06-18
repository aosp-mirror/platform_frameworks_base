/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.recordissue

import android.annotation.SuppressLint
import android.app.AlertDialog.BUTTON_POSITIVE
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.UserHandle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Button
import android.widget.PopupMenu
import android.widget.Switch
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.flags.Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.SessionCreationSource
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDisabledDialogDelegate
import com.android.systemui.recordissue.IssueRecordingState.Companion.ALL_ISSUE_TYPES
import com.android.systemui.recordissue.IssueRecordingState.Companion.ISSUE_TYPE_NOT_SET
import com.android.systemui.recordissue.IssueRecordingState.Companion.KEY_ISSUE_TYPE_RES
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.Executor

class RecordIssueDialogDelegate
@AssistedInject
constructor(
    private val factory: SystemUIDialog.Factory,
    private val userTracker: UserTracker,
    private val flags: FeatureFlagsClassic,
    @Background private val bgExecutor: Executor,
    @Main private val mainExecutor: Executor,
    private val devicePolicyResolver: dagger.Lazy<ScreenCaptureDevicePolicyResolver>,
    private val mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    private val screenCaptureDisabledDialogDelegate: ScreenCaptureDisabledDialogDelegate,
    private val state: IssueRecordingState,
    private val traceurMessageSender: TraceurMessageSender,
    @Assisted private val onStarted: Runnable,
) : SystemUIDialog.Delegate {

    /** To inject dependencies and allow for easier testing */
    @AssistedFactory
    interface Factory {
        /** Create a dialog object */
        fun create(onStarted: Runnable): RecordIssueDialogDelegate
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode") private lateinit var screenRecordSwitch: Switch
    private lateinit var issueTypeButton: Button

    @MainThread
    override fun beforeCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            setView(LayoutInflater.from(context).inflate(R.layout.record_issue_dialog, null))
            setTitle(context.getString(R.string.qs_record_issue_label))
            setIcon(R.drawable.qs_record_issue_icon_off)
            setNegativeButton(R.string.cancel) { _, _ -> }
            setPositiveButton(R.string.qs_record_issue_start) { _, _ -> onStarted.run() }
        }
        bgExecutor.execute { traceurMessageSender.bindToTraceur(dialog.context) }
    }

    override fun createDialog(): SystemUIDialog = factory.create(this)

    @MainThread
    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        dialog.apply {
            window?.apply {
                addPrivateFlags(WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS)
                setGravity(Gravity.CENTER)
            }

            screenRecordSwitch =
                requireViewById<Switch>(R.id.screenrecord_switch).apply {
                    isChecked = state.recordScreen
                    setOnCheckedChangeListener { _, isChecked ->
                        state.recordScreen = isChecked
                        if (isChecked) {
                            bgExecutor.execute { onScreenRecordSwitchClicked() }
                        }
                    }
                }

            requireViewById<Switch>(R.id.bugreport_switch).apply {
                isChecked = state.takeBugreport
                setOnCheckedChangeListener { _, isChecked -> state.takeBugreport = isChecked }
            }

            issueTypeButton =
                requireViewById<Button>(R.id.issue_type_button).apply {
                    val startButton = dialog.getButton(BUTTON_POSITIVE)
                    if (state.issueTypeRes != ISSUE_TYPE_NOT_SET) {
                        setText(state.issueTypeRes)
                    } else {
                        startButton.isEnabled = false
                    }
                    setOnClickListener {
                        onIssueTypeClicked(context) { startButton.isEnabled = true }
                    }
                }
        }
    }

    @WorkerThread
    private fun onScreenRecordSwitchClicked() {
        if (
            flags.isEnabled(WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES) &&
                devicePolicyResolver
                    .get()
                    .isScreenCaptureCompletelyDisabled(UserHandle.of(userTracker.userId))
        ) {
            mainExecutor.execute {
                screenCaptureDisabledDialogDelegate.createSysUIDialog().show()
                screenRecordSwitch.isChecked = false
            }
            return
        }

        mediaProjectionMetricsLogger.notifyProjectionInitiated(
            userTracker.userId,
            SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER
        )

        if (
            flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING) &&
                !state.hasUserApprovedScreenRecording
        ) {
            mainExecutor.execute {
                ScreenCapturePermissionDialogDelegate(factory, state).createDialog().apply {
                    setOnCancelListener { screenRecordSwitch.isChecked = false }
                    show()
                }
            }
        }
    }

    @MainThread
    private fun onIssueTypeClicked(context: Context, onIssueTypeSelected: Runnable) {
        val popupMenu = PopupMenu(context, issueTypeButton)

        ALL_ISSUE_TYPES.keys.forEach {
            popupMenu.menu.add(it).apply {
                setIcon(R.drawable.arrow_pointing_down)
                if (it != state.issueTypeRes) {
                    iconTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                }
                intent = Intent().putExtra(KEY_ISSUE_TYPE_RES, it)
            }
        }
        popupMenu.apply {
            setOnMenuItemClickListener {
                issueTypeButton.text = it.title
                state.issueTypeRes =
                    it.intent?.getIntExtra(KEY_ISSUE_TYPE_RES, ISSUE_TYPE_NOT_SET)
                        ?: ISSUE_TYPE_NOT_SET
                onIssueTypeSelected.run()
                true
            }
            setForceShowIcon(true)
            show()
        }
    }
}
