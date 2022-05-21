/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaRoute2Info
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.view.ViewUtil

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaTttChipControllerSenderTest : SysuiTestCase() {
    private lateinit var controllerSender: MediaTttChipControllerSender

    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var applicationInfo: ApplicationInfo
    @Mock
    private lateinit var logger: MediaTttLogger
    @Mock
    private lateinit var powerManager: PowerManager
    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var viewUtil: ViewUtil
    @Mock
    private lateinit var commandQueue: CommandQueue
    private lateinit var commandQueueCallback: CommandQueue.Callbacks
    private lateinit var fakeAppIconDrawable: Drawable
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var senderUiEventLogger: MediaTttSenderUiEventLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        fakeAppIconDrawable = context.getDrawable(R.drawable.ic_cake)!!
        whenever(applicationInfo.loadLabel(packageManager)).thenReturn(APP_NAME)
        whenever(packageManager.getApplicationIcon(PACKAGE_NAME)).thenReturn(fakeAppIconDrawable)
        whenever(packageManager.getApplicationInfo(
            eq(PACKAGE_NAME), any<PackageManager.ApplicationInfoFlags>()
        )).thenReturn(applicationInfo)
        context.setMockPackageManager(packageManager)

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)
        uiEventLoggerFake = UiEventLoggerFake()
        senderUiEventLogger = MediaTttSenderUiEventLogger(uiEventLoggerFake)

        controllerSender = MediaTttChipControllerSender(
            commandQueue,
            context,
            logger,
            windowManager,
            viewUtil,
            fakeExecutor,
            TapGestureDetector(context),
            powerManager,
            senderUiEventLogger
        )

        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        commandQueueCallback = callbackCaptor.value!!
    }

    @Test
    fun commandQueueCallback_almostCloseToStartCast_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            almostCloseToStartCast().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_START_CAST.id
        )
    }

    @Test
    fun commandQueueCallback_almostCloseToEndCast_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            almostCloseToEndCast().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_END_CAST.id
        )
    }

    @Test
    fun commandQueueCallback_transferToReceiverTriggered_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToReceiverTriggered().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_TRIGGERED.id
        )
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceTriggered_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToThisDeviceTriggered().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_TRIGGERED.id
        )
    }

    @Test
    fun commandQueueCallback_transferToReceiverSucceeded_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToReceiverSucceeded().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_SUCCEEDED.id
        )
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceSucceeded_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToThisDeviceSucceeded().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_SUCCEEDED.id
        )
    }

    @Test
    fun commandQueueCallback_transferToReceiverFailed_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToReceiverFailed().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED.id
        )
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceFailed_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
            routeInfo,
            null
        )

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToThisDeviceFailed().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_FAILED.id
        )
    }

    @Test
    fun commandQueueCallback_farFromReceiver_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )

        verify(windowManager, never()).addView(any(), any())
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_FAR_FROM_RECEIVER.id
        )
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
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            100,
            routeInfo,
            null
        )

        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun receivesNewStateFromCommandQueue_isLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        verify(logger).logStateChange(any(), any())
    }

    @Test
    fun almostCloseToStartCast_appIcon_deviceName_noLoadingIcon_noUndo_noFailureIcon() {
        val state = almostCloseToStartCast()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipView.getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun almostCloseToEndCast_appIcon_deviceName_noLoadingIcon_noUndo_noFailureIcon() {
        val state = almostCloseToEndCast()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipView.getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverTriggered_appIcon_loadingIcon_noUndo_noFailureIcon() {
        val state = transferToReceiverTriggered()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipView.getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToThisDeviceTriggered_appIcon_loadingIcon_noUndo_noFailureIcon() {
        val state = transferToThisDeviceTriggered()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipView.getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverSucceeded_appIcon_deviceName_noLoadingIcon_noFailureIcon() {
        val state = transferToReceiverSucceeded()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipView.getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverSucceeded_nullUndoRunnable_noUndo() {
        controllerSender.displayChip(transferToReceiverSucceeded(undoCallback = null))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverSucceeded_withUndoRunnable_undoWithClick() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayChip(transferToReceiverSucceeded(undoCallback))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun transferToReceiverSucceeded_withUndoRunnable_undoButtonClickRunsRunnable() {
        var undoCallbackCalled = false
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {
                undoCallbackCalled = true
            }
        }

        controllerSender.displayChip(transferToReceiverSucceeded(undoCallback))
        getChipView().getUndoButton().performClick()

        assertThat(undoCallbackCalled).isTrue()
    }

    @Test
    fun transferToReceiverSucceeded_undoButtonClick_switchesToTransferToThisDeviceTriggered() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayChip(transferToReceiverSucceeded(undoCallback))

        getChipView().getUndoButton().performClick()

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToThisDeviceTriggered().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_RECEIVER_CLICKED.id
        )
    }

    @Test
    fun transferToThisDeviceSucceeded_appIcon_deviceName_noLoadingIcon_noFailureIcon() {
        val state = transferToThisDeviceSucceeded()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipView.getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToThisDeviceSucceeded_nullUndoRunnable_noUndo() {
        controllerSender.displayChip(transferToThisDeviceSucceeded(undoCallback = null))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToThisDeviceSucceeded_withUndoRunnable_undoWithClick() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayChip(transferToThisDeviceSucceeded(undoCallback))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipView.getUndoButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun transferToThisDeviceSucceeded_withUndoRunnable_undoButtonClickRunsRunnable() {
        var undoCallbackCalled = false
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {
                undoCallbackCalled = true
            }
        }

        controllerSender.displayChip(transferToThisDeviceSucceeded(undoCallback))
        getChipView().getUndoButton().performClick()

        assertThat(undoCallbackCalled).isTrue()
    }

    @Test
    fun transferToThisDeviceSucceeded_undoButtonClick_switchesToTransferToReceiverTriggered() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayChip(transferToThisDeviceSucceeded(undoCallback))

        getChipView().getUndoButton().performClick()

        assertThat(getChipView().getChipText()).isEqualTo(
            transferToReceiverTriggered().state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED.id
        )
    }

    @Test
    fun transferToReceiverFailed_appIcon_noDeviceName_noLoadingIcon_noUndo_failureIcon() {
        val state = transferToReceiverFailed()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(getChipView().getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun transferToThisDeviceFailed_appIcon_noDeviceName_noLoadingIcon_noUndo_failureIcon() {
        val state = transferToThisDeviceFailed()
        controllerSender.displayChip(state)

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(getChipView().getChipText()).isEqualTo(
            state.state.getChipTextString(context, OTHER_DEVICE_NAME)
        )
        assertThat(chipView.getLoadingIconVisibility()).isEqualTo(View.GONE)
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipView.getFailureIcon().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromAlmostCloseToStartToTransferTriggered_loadingIconAppears() {
        controllerSender.displayChip(almostCloseToStartCast())
        controllerSender.displayChip(transferToReceiverTriggered())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferSucceeded_loadingIconDisappears() {
        controllerSender.displayChip(transferToReceiverTriggered())
        controllerSender.displayChip(transferToReceiverSucceeded())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferSucceeded_undoButtonAppears() {
        controllerSender.displayChip(transferToReceiverTriggered())
        controllerSender.displayChip(
            transferToReceiverSucceeded(
                object : IUndoMediaTransferCallback.Stub() {
                    override fun onUndoTriggered() {}
                }
            )
        )

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferSucceededToAlmostCloseToStart_undoButtonDisappears() {
        controllerSender.displayChip(transferToReceiverSucceeded())
        controllerSender.displayChip(almostCloseToStartCast())

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferFailed_failureIconAppears() {
        controllerSender.displayChip(transferToReceiverTriggered())
        controllerSender.displayChip(transferToReceiverFailed())

        assertThat(getChipView().getFailureIcon().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun transferToReceiverTriggeredThenRemoveChip_chipStillDisplayed() {
        controllerSender.displayChip(transferToReceiverTriggered())
        fakeClock.advanceTime(1000L)

        controllerSender.removeChip("fakeRemovalReason")
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun transferToReceiverTriggeredThenFarFromReceiver_eventuallyTimesOut() {
        val state = transferToReceiverTriggered()
        controllerSender.displayChip(state)
        fakeClock.advanceTime(1000L)
        controllerSender.removeChip("fakeRemovalReason")

        fakeClock.advanceTime(state.state.timeout + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenRemoveChip_chipStillDisplayed() {
        controllerSender.displayChip(transferToThisDeviceTriggered())
        fakeClock.advanceTime(1000L)

        controllerSender.removeChip("fakeRemovalReason")
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenFarFromReceiver_eventuallyTimesOut() {
        val state = transferToThisDeviceTriggered()
        controllerSender.displayChip(state)
        fakeClock.advanceTime(1000L)
        controllerSender.removeChip("fakeRemovalReason")

        fakeClock.advanceTime(state.state.timeout + 1)

        verify(windowManager).removeView(any())
    }

    private fun LinearLayout.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)

    private fun LinearLayout.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun LinearLayout.getLoadingIconVisibility(): Int =
        this.requireViewById<View>(R.id.loading).visibility

    private fun LinearLayout.getUndoButton(): View = this.requireViewById(R.id.undo)

    private fun LinearLayout.getFailureIcon(): View = this.requireViewById(R.id.failure_icon)

    private fun getChipView(): LinearLayout {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as LinearLayout
    }

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

private const val APP_NAME = "Fake app name"
private const val OTHER_DEVICE_NAME = "My Tablet"
private const val PACKAGE_NAME = "com.android.systemui"

private val routeInfo = MediaRoute2Info.Builder("id", OTHER_DEVICE_NAME)
    .addFeature("feature")
    .setPackageName(PACKAGE_NAME)
    .build()
