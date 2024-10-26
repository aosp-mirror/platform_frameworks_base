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

package com.android.systemui.recordissue

import android.app.IActivityManager
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.settings.UserContextProvider
import com.android.traceur.PresetTraceConfigs
import java.util.concurrent.Executor

private const val SHELL_PACKAGE = "com.android.shell"
private const val NOTIFY_SESSION_ENDED_SETTING = "should_notify_trace_session_ended"
private const val DISABLED = 0

/**
 * This class exists to unit test the business logic encapsulated in IssueRecordingService. Android
 * specifically calls out that there is no supported way to test IntentServices here:
 * https://developer.android.com/training/testing/other-components/services, and mentions that the
 * best way to add unit tests, is to introduce a separate class containing the business logic of
 * that service, and test the functionality via that class.
 */
class IssueRecordingServiceSession(
    private val bgExecutor: Executor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val traceurConnection: TraceurConnection,
    private val issueRecordingState: IssueRecordingState,
    private val iActivityManager: IActivityManager,
    private val notificationManager: NotificationManager,
    private val userContextProvider: UserContextProvider,
    private val startTimeStore: ScreenRecordingStartTimeStore,
) {
    var takeBugReport = false
    var traceConfig = PresetTraceConfigs.getDefaultConfig()
    var screenRecord = false

    fun start() {
        bgExecutor.execute {
            traceurConnection.startTracing(traceConfig)
            issueRecordingState.isRecording = true
        }
    }

    fun stop() {
        bgExecutor.execute {
            if (traceConfig.longTrace) {
                Settings.Global.putInt(
                    userContextProvider.userContext.contentResolver,
                    NOTIFY_SESSION_ENDED_SETTING,
                    DISABLED,
                )
            }
            traceurConnection.stopTracing()
            issueRecordingState.isRecording = false
        }
    }

    fun share(notificationId: Int, screenRecording: Uri?) {
        bgExecutor.execute {
            notificationManager.cancelAsUser(
                null,
                notificationId,
                UserHandle(userContextProvider.userContext.userId),
            )
            val screenRecordingUris: List<Uri> =
                mutableListOf<Uri>().apply {
                    screenRecording?.let { add(it) }
                    if (traceConfig.winscope && screenRecord) {
                        startTimeStore.getFileUri(userContextProvider.userContext)?.let { add(it) }
                    }
                }
            if (takeBugReport) {
                screenRecordingUris.forEach {
                    userContextProvider.userContext.grantUriPermission(
                        SHELL_PACKAGE,
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                iActivityManager.requestBugReportWithExtraAttachments(screenRecordingUris)
            } else {
                traceurConnection.shareTraces(screenRecordingUris)
            }
        }

        dialogTransitionAnimator.disableAllCurrentDialogsExitAnimations()
        panelInteractor.collapsePanels()
    }
}
