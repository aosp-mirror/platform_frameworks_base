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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.os.UserHandle
import android.view.MotionEvent.ACTION_MOVE
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import androidx.annotation.LayoutRes
import androidx.annotation.StyleRes
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.appselector.MediaProjectionAppSelectorActivity
import com.android.systemui.mediaprojection.permission.BaseMediaProjectionPermissionDialogDelegate
import com.android.systemui.mediaprojection.permission.ENTIRE_SCREEN
import com.android.systemui.mediaprojection.permission.SINGLE_APP
import com.android.systemui.mediaprojection.permission.ScreenShareMode
import com.android.systemui.mediaprojection.permission.ScreenShareOption
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
    mediaProjectionMetricsLogger: MediaProjectionMetricsLogger,
    private val systemUIDialogFactory: SystemUIDialog.Factory,
    @ScreenShareMode defaultSelectedMode: Int,
    @StyleRes private val theme: Int,
    private val context: Context,
) :
    BaseMediaProjectionPermissionDialogDelegate<SystemUIDialog>(
        createOptionList(),
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

    private lateinit var tapsSwitch: Switch
    private lateinit var tapsView: View
    private lateinit var audioSwitch: Switch
    private lateinit var options: Spinner

    override fun createDialog(): SystemUIDialog {
        return systemUIDialogFactory.create(this, context, theme)
    }

    override fun onCreate(dialog: SystemUIDialog, savedInstanceState: Bundle?) {
        super<BaseMediaProjectionPermissionDialogDelegate>.onCreate(dialog, savedInstanceState)
        setDialogTitle(R.string.screenrecord_permission_dialog_title)
        dialog.setTitle(R.string.screenrecord_title)
        setStartButtonText(R.string.screenrecord_permission_dialog_continue)
        setStartButtonOnClickListener { v: View? ->
            onStartRecordingClicked?.run()
            if (selectedScreenShareOption.mode == ENTIRE_SCREEN) {
                requestScreenCapture(/* captureTarget= */ null)
            }
            if (selectedScreenShareOption.mode == SINGLE_APP) {
                val intent = Intent(dialog.context, MediaProjectionAppSelectorActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // We can't start activity for result here so we use result receiver to get
                // the selected target to capture
                intent.putExtra(
                    MediaProjectionAppSelectorActivity.EXTRA_CAPTURE_REGION_RESULT_RECEIVER,
                    CaptureTargetResultReceiver()
                )

                intent.putExtra(
                    MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_USER_HANDLE,
                    hostUserHandle
                )
                intent.putExtra(MediaProjectionAppSelectorActivity.EXTRA_HOST_APP_UID, hostUid)
                activityStarter.startActivity(intent, /* dismissShade= */ true)
            }
            dialog.dismiss()
        }
        setCancelButtonOnClickListener { dialog.dismiss() }
        initRecordOptionsView()
    }

    @LayoutRes override fun getOptionsViewLayoutId(): Int = R.layout.screen_record_options

    @SuppressLint("ClickableViewAccessibility")
    private fun initRecordOptionsView() {
        audioSwitch = dialog.requireViewById(R.id.screenrecord_audio_switch)
        tapsSwitch = dialog.requireViewById(R.id.screenrecord_taps_switch)

        // Add these listeners so that the switch only responds to movement
        // within its target region, to meet accessibility requirements
        audioSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }
        tapsSwitch.setOnTouchListener { _, event -> event.action == ACTION_MOVE }

        tapsView = dialog.requireViewById(R.id.show_taps)
        updateTapsViewVisibility()

        options = dialog.requireViewById(R.id.screen_recording_options)
        val a: ArrayAdapter<*> =
            ScreenRecordingAdapter(
                dialog.context,
                android.R.layout.simple_spinner_dropdown_item,
                MODES
            )
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        options.adapter = a
        options.setOnItemClickListenerInt { _: AdapterView<*>?, _: View?, _: Int, _: Long ->
            audioSwitch.isChecked = true
        }

        // disable redundant Touch & Hold accessibility action for Switch Access
        options.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo
                ) {
                    info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
            }
        options.isLongClickable = false
    }

    override fun onItemSelected(adapterView: AdapterView<*>?, view: View, pos: Int, id: Long) {
        super.onItemSelected(adapterView, view, pos, id)
        updateTapsViewVisibility()
    }

    private fun updateTapsViewVisibility() {
        tapsView.visibility = if (selectedScreenShareOption.mode == SINGLE_APP) GONE else VISIBLE
    }

    /**
     * Starts screen capture after some countdown
     *
     * @param captureTarget target to capture (could be e.g. a task) or null to record the whole
     *   screen
     */
    private fun requestScreenCapture(captureTarget: MediaProjectionCaptureTarget?) {
        val userContext = userContextProvider.userContext
        val showTaps = selectedScreenShareOption.mode != SINGLE_APP && tapsSwitch.isChecked
        val audioMode =
            if (audioSwitch.isChecked) options.selectedItem as ScreenRecordingAudioSource
            else ScreenRecordingAudioSource.NONE
        val startIntent =
            PendingIntent.getForegroundService(
                userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStartIntent(
                    userContext,
                    Activity.RESULT_OK,
                    audioMode.ordinal,
                    showTaps,
                    captureTarget
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        val stopIntent =
            PendingIntent.getService(
                userContext,
                RecordingService.REQUEST_CODE,
                RecordingService.getStopIntent(userContext),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        controller.startCountdown(DELAY_MS, INTERVAL_MS, startIntent, stopIntent)
    }

    private inner class CaptureTargetResultReceiver() :
        ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle) {
            if (resultCode == Activity.RESULT_OK) {
                val captureTarget =
                    resultData.getParcelable(
                        MediaProjectionAppSelectorActivity.KEY_CAPTURE_TARGET,
                        MediaProjectionCaptureTarget::class.java
                    )

                // Start recording of the selected target
                requestScreenCapture(captureTarget)
            }
        }
    }

    companion object {
        private val MODES =
            listOf(
                ScreenRecordingAudioSource.INTERNAL,
                ScreenRecordingAudioSource.MIC,
                ScreenRecordingAudioSource.MIC_AND_INTERNAL
            )
        private const val DELAY_MS: Long = 3000
        private const val INTERVAL_MS: Long = 1000

        private fun createOptionList(): List<ScreenShareOption> {
            return listOf(
                ScreenShareOption(
                    SINGLE_APP,
                    R.string.screen_share_permission_dialog_option_single_app,
                    R.string.screenrecord_permission_dialog_warning_single_app
                ),
                ScreenShareOption(
                    ENTIRE_SCREEN,
                    R.string.screen_share_permission_dialog_option_entire_screen,
                    R.string.screenrecord_permission_dialog_warning_entire_screen
                )
            )
        }
    }
}
