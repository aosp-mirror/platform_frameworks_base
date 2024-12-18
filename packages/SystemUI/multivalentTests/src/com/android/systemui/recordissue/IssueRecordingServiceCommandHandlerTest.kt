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
import android.net.Uri
import android.os.UserHandle
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.dialogTransitionAnimator
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.settings.userFileManager
import com.android.systemui.settings.userTracker
import com.android.traceur.TraceConfig
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class IssueRecordingServiceCommandHandlerTest : SysuiTestCase() {

    private val kosmos = Kosmos().also { it.testCase = this }
    private val bgExecutor = kosmos.fakeExecutor
    private val userContextProvider: UserContextProvider = kosmos.userTracker
    private val dialogTransitionAnimator: DialogTransitionAnimator = kosmos.dialogTransitionAnimator
    private lateinit var traceurMessageSender: TraceurMessageSender
    private val issueRecordingState =
        IssueRecordingState(kosmos.userTracker, kosmos.userFileManager)

    private val iActivityManager = mock<IActivityManager>()
    private val notificationManager = mock<NotificationManager>()
    private val panelInteractor = mock<PanelInteractor>()

    private lateinit var underTest: IssueRecordingServiceCommandHandler

    @Before
    fun setup() {
        traceurMessageSender = mock<TraceurMessageSender>()
        underTest =
            IssueRecordingServiceCommandHandler(
                bgExecutor,
                dialogTransitionAnimator,
                panelInteractor,
                traceurMessageSender,
                issueRecordingState,
                iActivityManager,
                notificationManager,
                userContextProvider
            )
    }

    @Test
    fun startsTracing_afterReceivingActionStartCommand() {
        underTest.handleStartCommand()
        bgExecutor.runAllReady()

        Truth.assertThat(issueRecordingState.isRecording).isTrue()
        verify(traceurMessageSender).startTracing(any<TraceConfig>())
    }

    @Test
    fun stopsTracing_afterReceivingStopTracingCommand() {
        underTest.handleStopCommand(mContext.contentResolver)
        bgExecutor.runAllReady()

        Truth.assertThat(issueRecordingState.isRecording).isFalse()
        verify(traceurMessageSender).stopTracing()
    }

    @Test
    fun cancelsNotification_afterReceivingShareCommand() {
        underTest.handleShareCommand(0, null, mContext)
        bgExecutor.runAllReady()

        verify(notificationManager).cancelAsUser(isNull(), anyInt(), any<UserHandle>())
    }

    @Test
    fun requestBugreport_afterReceivingShareCommand_withTakeBugreportTrue() {
        issueRecordingState.takeBugreport = true
        val uri = mock<Uri>()

        underTest.handleShareCommand(0, uri, mContext)
        bgExecutor.runAllReady()

        verify(iActivityManager).requestBugReportWithExtraAttachment(uri)
    }

    @Test
    fun sharesTracesDirectly_afterReceivingShareCommand_withTakeBugreportFalse() {
        issueRecordingState.takeBugreport = false
        val uri = mock<Uri>()

        underTest.handleShareCommand(0, uri, mContext)
        bgExecutor.runAllReady()

        verify(traceurMessageSender).shareTraces(mContext, uri)
    }

    @Test
    fun closesShade_afterReceivingShareCommand() {
        val uri = mock<Uri>()

        underTest.handleShareCommand(0, uri, mContext)
        bgExecutor.runAllReady()

        verify(panelInteractor).collapsePanels()
    }
}
