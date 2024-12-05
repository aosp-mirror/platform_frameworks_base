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

package com.android.systemui.qs.tiles.impl.screenrecord.domain.interactor

import android.app.Dialog
import android.media.projection.StopReason
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.plugins.ActivityStarter.OnDismissAction
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileInputTestKtx
import com.android.systemui.screenrecord.RecordingController
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.ScreenRecordRepositoryImpl
import com.android.systemui.statusbar.phone.KeyguardDismissUtil
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class ScreenRecordTileUserActionInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val keyguardInteractor = kosmos.keyguardInteractor
    private val dialogTransitionAnimator = mock<DialogTransitionAnimator>()
    private val keyguardDismissUtil = mock<KeyguardDismissUtil>()
    private val panelInteractor = mock<PanelInteractor>()
    private val dialog = mock<Dialog>()
    private val recordingController =
        mock<RecordingController> { on { createScreenRecordDialog(any()) } doReturn dialog }

    private val screenRecordRepository =
        ScreenRecordRepositoryImpl(
            bgCoroutineContext = testScope.testScheduler,
            recordingController = recordingController,
        )

    private val underTest =
        ScreenRecordTileUserActionInteractor(
            testScope.testScheduler,
            testScope.testScheduler,
            screenRecordRepository,
            recordingController,
            keyguardInteractor,
            keyguardDismissUtil,
            dialogTransitionAnimator,
            panelInteractor,
            mock<MediaProjectionMetricsLogger>(),
        )

    @Test
    fun handleClick_whenStarting_cancelCountdown() = runTest {
        val startingModel = ScreenRecordModel.Starting(0)

        underTest.handleInput(QSTileInputTestKtx.click(startingModel))

        verify(recordingController).cancelCountdown()
    }

    @Test
    fun handleClick_whenRecording_stopRecording() = runTest {
        val recordingModel = ScreenRecordModel.Recording

        underTest.handleInput(QSTileInputTestKtx.click(recordingModel))

        verify(recordingController).stopRecording(eq(StopReason.STOP_QS_TILE))
    }

    @Test
    fun handleClick_whenDoingNothing_createDialogDismissPanelShowDialog() = runTest {
        val recordingModel = ScreenRecordModel.DoingNothing

        underTest.handleInput(QSTileInputTestKtx.click(recordingModel))
        val onStartRecordingClickedCaptor = argumentCaptor<Runnable>()
        verify(recordingController)
            .createScreenRecordDialog(onStartRecordingClickedCaptor.capture())

        val onDismissActionCaptor = argumentCaptor<OnDismissAction>()
        verify(keyguardDismissUtil)
            .executeWhenUnlocked(onDismissActionCaptor.capture(), eq(false), eq(true))
        onDismissActionCaptor.lastValue.onDismiss()
        verify(dialog).show() // because the view was null

        // When starting the recording, we collapse the shade and disable the dialog animation.
        onStartRecordingClickedCaptor.lastValue.run()
        verify(dialogTransitionAnimator).disableAllCurrentDialogsExitAnimations()
        verify(panelInteractor).collapsePanels()
    }

    /**
     * When the input view is not null and keyguard is not showing, dialog should animate and show
     */
    @Test
    fun handleClickFromView_whenDoingNothing_whenKeyguardNotShowing_showDialogFromView() = runTest {
        val controller = mock<DialogTransitionAnimator.Controller>()
        val expandable =
            mock<Expandable> { on { dialogTransitionController(any()) } doReturn controller }

        kosmos.fakeKeyguardRepository.setKeyguardShowing(false)

        val recordingModel = ScreenRecordModel.DoingNothing

        underTest.handleInput(
            QSTileInputTestKtx.click(recordingModel, UserHandle.CURRENT, expandable)
        )
        val onStartRecordingClickedCaptor = argumentCaptor<Runnable>()
        verify(recordingController)
            .createScreenRecordDialog(onStartRecordingClickedCaptor.capture())

        val onDismissActionCaptor = argumentCaptor<OnDismissAction>()
        verify(keyguardDismissUtil)
            .executeWhenUnlocked(onDismissActionCaptor.capture(), eq(false), eq(true))
        onDismissActionCaptor.lastValue.onDismiss()
        verify(dialogTransitionAnimator).show(eq(dialog), eq(controller), eq(true))
    }
}
