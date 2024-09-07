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
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.settings.UserContextProvider
import java.util.concurrent.Executor

private const val NOTIFY_SESSION_ENDED_SETTING = "should_notify_trace_session_ended"
private const val DISABLED = 0

/**
 * This class exists to unit test the business logic encapsulated in IssueRecordingService. Android
 * specifically calls out that there is no supported way to test IntentServices here:
 * https://developer.android.com/training/testing/other-components/services
 */
class IssueRecordingServiceCommandHandler(
    private val bgExecutor: Executor,
    private val dialogTransitionAnimator: DialogTransitionAnimator,
    private val panelInteractor: PanelInteractor,
    private val traceurMessageSender: TraceurMessageSender,
    private val issueRecordingState: IssueRecordingState,
    private val iActivityManager: IActivityManager,
    private val notificationManager: NotificationManager,
    private val userContextProvider: UserContextProvider,
) {

    fun handleStartCommand() {
        bgExecutor.execute { traceurMessageSender.startTracing(issueRecordingState.traceConfig) }
        issueRecordingState.isRecording = true
    }

    fun handleStopCommand(contentResolver: ContentResolver) {
        bgExecutor.execute {
            if (issueRecordingState.traceConfig.longTrace) {
                Settings.Global.putInt(contentResolver, NOTIFY_SESSION_ENDED_SETTING, DISABLED)
            }
            traceurMessageSender.stopTracing()
        }
        issueRecordingState.isRecording = false
    }

    fun handleShareCommand(notificationId: Int, screenRecording: Uri?, context: Context) {
        bgExecutor.execute {
            notificationManager.cancelAsUser(
                null,
                notificationId,
                UserHandle(userContextProvider.userContext.userId)
            )

            if (issueRecordingState.takeBugreport) {
                iActivityManager.requestBugReportWithExtraAttachment(screenRecording)
            } else {
                traceurMessageSender.shareTraces(context, screenRecording)
            }
        }

        dialogTransitionAnimator.disableAllCurrentDialogsExitAnimations()
        panelInteractor.collapsePanels()
    }
}
