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

import android.app.Dialog
import android.content.SharedPreferences
import android.os.UserHandle
import android.testing.TestableLooper
import android.widget.Button
import android.widget.Switch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.SessionCreationSource
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDisabledDialogDelegate
import com.android.systemui.model.SysUiState
import com.android.systemui.recordissue.IssueRecordingState.Companion.ISSUE_TYPE_NOT_SET
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class RecordIssueDialogDelegateTest : SysuiTestCase() {

    @Mock private lateinit var flags: FeatureFlagsClassic
    @Mock private lateinit var devicePolicyResolver: ScreenCaptureDevicePolicyResolver
    @Mock private lateinit var dprLazy: dagger.Lazy<ScreenCaptureDevicePolicyResolver>
    @Mock private lateinit var mediaProjectionMetricsLogger: MediaProjectionMetricsLogger
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var state: IssueRecordingState
    @Mock private lateinit var sharedPreferences: SharedPreferences
    @Mock
    private lateinit var screenCaptureDisabledDialogDelegate: ScreenCaptureDisabledDialogDelegate
    @Mock private lateinit var screenCaptureDisabledDialog: SystemUIDialog

    @Mock private lateinit var sysuiState: SysUiState
    @Mock private lateinit var systemUIDialogManager: SystemUIDialogManager
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var traceurMessageSender: TraceurMessageSender
    private val systemClock = FakeSystemClock()
    private val bgExecutor = FakeExecutor(systemClock)
    private val mainExecutor = FakeExecutor(systemClock)
    @Mock private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator

    private lateinit var dialog: SystemUIDialog
    private lateinit var factory: SystemUIDialog.Factory
    private lateinit var latch: CountDownLatch

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(dprLazy.get()).thenReturn(devicePolicyResolver)
        whenever(sysuiState.setFlag(anyLong(), anyBoolean())).thenReturn(sysuiState)
        whenever(screenCaptureDisabledDialogDelegate.createSysUIDialog())
            .thenReturn(screenCaptureDisabledDialog)
        whenever(state.issueTypeRes).thenReturn(ISSUE_TYPE_NOT_SET)

        factory =
            spy(
                SystemUIDialog.Factory(
                    context,
                    systemUIDialogManager,
                    sysuiState,
                    broadcastDispatcher,
                    mDialogTransitionAnimator
                )
            )

        latch = CountDownLatch(1)
        dialog =
            RecordIssueDialogDelegate(
                    factory,
                    userTracker,
                    flags,
                    bgExecutor,
                    mainExecutor,
                    dprLazy,
                    mediaProjectionMetricsLogger,
                    screenCaptureDisabledDialogDelegate,
                    state,
                    traceurMessageSender
                ) {
                    latch.countDown()
                }
                .createDialog()
        dialog.show()
    }

    @After
    fun teardown() {
        dialog.dismiss()
    }

    @Test
    fun dialog_hasCorrectUiElements_afterCreation() {
        dialog.requireViewById<Switch>(R.id.screenrecord_switch)
        dialog.requireViewById<Button>(R.id.issue_type_button)

        assertThat(dialog.getButton(Dialog.BUTTON_POSITIVE).text)
            .isEqualTo(context.getString(R.string.qs_record_issue_start))
        assertThat(dialog.getButton(Dialog.BUTTON_NEGATIVE).text)
            .isEqualTo(context.getString(R.string.cancel))
    }

    @Test
    fun onStarted_isCalled_afterStartButtonIsClicked() {
        dialog.getButton(Dialog.BUTTON_POSITIVE).callOnClick()
        latch.await(1L, TimeUnit.MILLISECONDS)
    }

    @Test
    fun screenCaptureDisabledDialog_isShown_whenFunctionalityIsDisabled() {
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES))
            .thenReturn(true)
        whenever(devicePolicyResolver.isScreenCaptureCompletelyDisabled(any<UserHandle>()))
            .thenReturn(true)

        val screenRecordSwitch = dialog.requireViewById<Switch>(R.id.screenrecord_switch)
        screenRecordSwitch.isChecked = true

        bgExecutor.runAllReady()
        mainExecutor.runAllReady()

        verify(mediaProjectionMetricsLogger, never())
            .notifyProjectionInitiated(
                anyInt(),
                eq(SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER)
            )
        assertThat(screenRecordSwitch.isChecked).isFalse()
    }

    @Test
    fun screenCapturePermissionDialog_isShown_correctly() {
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES))
            .thenReturn(false)
        whenever(devicePolicyResolver.isScreenCaptureCompletelyDisabled(any<UserHandle>()))
            .thenReturn(false)
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)).thenReturn(true)
        whenever(state.hasUserApprovedScreenRecording).thenReturn(false)

        val screenRecordSwitch = dialog.requireViewById<Switch>(R.id.screenrecord_switch)
        screenRecordSwitch.isChecked = true

        bgExecutor.runAllReady()
        mainExecutor.runAllReady()

        verify(mediaProjectionMetricsLogger)
            .notifyProjectionInitiated(
                anyInt(),
                eq(SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER)
            )
        verify(factory).create(any<ScreenCapturePermissionDialogDelegate>())
    }

    @Test
    fun noDialogsAreShown_forScreenRecord_whenApprovalIsAlreadyGiven() {
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES))
            .thenReturn(false)
        whenever(devicePolicyResolver.isScreenCaptureCompletelyDisabled(any<UserHandle>()))
            .thenReturn(false)
        whenever(flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)).thenReturn(false)

        val screenRecordSwitch = dialog.requireViewById<Switch>(R.id.screenrecord_switch)
        screenRecordSwitch.isChecked = true

        bgExecutor.runAllReady()

        verify(mediaProjectionMetricsLogger)
            .notifyProjectionInitiated(
                anyInt(),
                eq(SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER)
            )
        verify(factory, never()).create(any<ScreenCapturePermissionDialogDelegate>())
    }

    @Test
    fun startButton_isDisabled_beforeIssueTypeIsSelected() {
        assertThat(dialog.getButton(Dialog.BUTTON_POSITIVE).isEnabled).isFalse()
    }
}
