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

package com.android.systemui.media.taptotransfer.sender

import android.app.StatusBarManager
import android.media.MediaRoute2Info
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.chipbar.ChipSenderInfo
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.FakeChipbarCoordinator
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.view.ViewUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaTttSenderCoordinatorTest : SysuiTestCase() {
    private lateinit var underTest: MediaTttSenderCoordinator

    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var logger: MediaTttLogger
    @Mock private lateinit var mediaTttFlags: MediaTttFlags
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var viewUtil: ViewUtil
    @Mock private lateinit var windowManager: WindowManager
    private lateinit var chipbarCoordinator: ChipbarCoordinator
    private lateinit var commandQueueCallback: CommandQueue.Callbacks
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var uiEventLogger: MediaTttSenderUiEventLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(mediaTttFlags.isMediaTttEnabled()).thenReturn(true)
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenReturn(1000)

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        uiEventLoggerFake = UiEventLoggerFake()
        uiEventLogger = MediaTttSenderUiEventLogger(uiEventLoggerFake)

        chipbarCoordinator =
            FakeChipbarCoordinator(
                context,
                logger,
                windowManager,
                fakeExecutor,
                accessibilityManager,
                configurationController,
                powerManager,
                uiEventLogger,
                falsingManager,
                falsingCollector,
                viewUtil,
            )
        chipbarCoordinator.start()

        underTest =
            MediaTttSenderCoordinator(
                chipbarCoordinator,
                commandQueue,
                context,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()

        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        commandQueueCallback = callbackCaptor.value!!
    }

    @Test
    fun commandQueueCallback_flagOff_noCallbackAdded() {
        reset(commandQueue)
        whenever(mediaTttFlags.isMediaTttEnabled()).thenReturn(false)
        underTest =
            MediaTttSenderCoordinator(
                chipbarCoordinator,
                commandQueue,
                context,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()

        verify(commandQueue, never()).addCallback(any())
    }

    @Test
    fun commandQueueCallback_almostCloseToStartCast_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(almostCloseToStartCast().state.getChipTextString(context, OTHER_DEVICE_NAME))
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_START_CAST.id)
    }

    @Test
    fun commandQueueCallback_almostCloseToEndCast_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(almostCloseToEndCast().state.getChipTextString(context, OTHER_DEVICE_NAME))
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_END_CAST.id)
    }

    @Test
    fun commandQueueCallback_transferToReceiverTriggered_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(
                transferToReceiverTriggered().state.getChipTextString(context, OTHER_DEVICE_NAME)
            )
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_TRIGGERED.id)
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceTriggered_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(
                transferToThisDeviceTriggered().state.getChipTextString(context, OTHER_DEVICE_NAME)
            )
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_TRIGGERED.id)
    }

    @Test
    fun commandQueueCallback_transferToReceiverSucceeded_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(
                transferToReceiverSucceeded().state.getChipTextString(context, OTHER_DEVICE_NAME)
            )
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_SUCCEEDED.id)
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceSucceeded_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(
                transferToThisDeviceSucceeded().state.getChipTextString(context, OTHER_DEVICE_NAME)
            )
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_SUCCEEDED.id)
    }

    @Test
    fun commandQueueCallback_transferToReceiverFailed_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(
                transferToReceiverFailed().state.getChipTextString(context, OTHER_DEVICE_NAME)
            )
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED.id)
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceFailed_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText())
            .isEqualTo(
                transferToThisDeviceFailed().state.getChipTextString(context, OTHER_DEVICE_NAME)
            )
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_FAILED.id)
    }

    @Test
    fun commandQueueCallback_farFromReceiver_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )

        verify(windowManager, never()).addView(any(), any())
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_FAR_FROM_RECEIVER.id)
    }

    @Test
    fun commandQueueCallback_almostCloseThenFarFromReceiver_chipShownThenHidden() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )

        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        verify(windowManager).removeView(viewCaptor.value)
    }

    @Test
    fun commandQueueCallback_invalidStateParam_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(100, routeInfo, null)

        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun receivesNewStateFromCommandQueue_isLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        verify(logger).logStateChange(any(), any(), any())
    }

    @Test
    fun transferToReceiverTriggeredThenFarFromReceiver_viewStillDisplayed() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null
        )

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenFarFromReceiver_viewStillDisplayed() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToReceiverSucceededThenFarFromReceiver_viewStillDisplayed() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToThisDeviceSucceededThenFarFromReceiver_viewStillDisplayed() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    private fun getChipView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    private fun ViewGroup.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun almostCloseToStartCast() =
        ChipSenderInfo(ChipStateSender.ALMOST_CLOSE_TO_START_CAST, routeInfo)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun almostCloseToEndCast() =
        ChipSenderInfo(ChipStateSender.ALMOST_CLOSE_TO_END_CAST, routeInfo)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToReceiverTriggered() =
        ChipSenderInfo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED, routeInfo)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToThisDeviceTriggered() =
        ChipSenderInfo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED, routeInfo)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToReceiverSucceeded(undoCallback: IUndoMediaTransferCallback? = null) =
        ChipSenderInfo(ChipStateSender.TRANSFER_TO_RECEIVER_SUCCEEDED, routeInfo, undoCallback)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToThisDeviceSucceeded(undoCallback: IUndoMediaTransferCallback? = null) =
        ChipSenderInfo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_SUCCEEDED, routeInfo, undoCallback)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToReceiverFailed() =
        ChipSenderInfo(ChipStateSender.TRANSFER_TO_RECEIVER_FAILED, routeInfo)

    /** Helper method providing default parameters to not clutter up the tests. */
    private fun transferToThisDeviceFailed() =
        ChipSenderInfo(ChipStateSender.TRANSFER_TO_RECEIVER_FAILED, routeInfo)
}

private const val OTHER_DEVICE_NAME = "My Tablet"

private val routeInfo =
    MediaRoute2Info.Builder("id", OTHER_DEVICE_NAME)
        .addFeature("feature")
        .setClientPackageName("com.android.systemui")
        .build()
