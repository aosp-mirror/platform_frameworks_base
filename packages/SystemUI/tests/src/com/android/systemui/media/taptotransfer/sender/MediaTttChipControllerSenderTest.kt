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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.media.taptotransfer.common.MediaTttLogger
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
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
    private lateinit var accessibilityManager: AccessibilityManager
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var powerManager: PowerManager
    @Mock
    private lateinit var windowManager: WindowManager
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

        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenReturn(TIMEOUT)

        controllerSender = MediaTttChipControllerSender(
            commandQueue,
            context,
            logger,
            windowManager,
            fakeExecutor,
            accessibilityManager,
            configurationController,
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

        verify(logger).logStateChange(any(), any(), any())
    }

    @Test
    fun almostCloseToStartCast_appIcon_deviceName_noLoadingIcon_noUndo_noFailureIcon() {
        val state = almostCloseToStartCast()
        controllerSender.displayView(state)

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(transferToReceiverSucceeded(undoCallback = null))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverSucceeded_withUndoRunnable_undoWithClick() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayView(transferToReceiverSucceeded(undoCallback))

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

        controllerSender.displayView(transferToReceiverSucceeded(undoCallback))
        getChipView().getUndoButton().performClick()

        assertThat(undoCallbackCalled).isTrue()
    }

    @Test
    fun transferToReceiverSucceeded_undoButtonClick_switchesToTransferToThisDeviceTriggered() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayView(transferToReceiverSucceeded(undoCallback))

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(transferToThisDeviceSucceeded(undoCallback = null))

        val chipView = getChipView()
        assertThat(chipView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToThisDeviceSucceeded_withUndoRunnable_undoWithClick() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayView(transferToThisDeviceSucceeded(undoCallback))

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

        controllerSender.displayView(transferToThisDeviceSucceeded(undoCallback))
        getChipView().getUndoButton().performClick()

        assertThat(undoCallbackCalled).isTrue()
    }

    @Test
    fun transferToThisDeviceSucceeded_undoButtonClick_switchesToTransferToReceiverTriggered() {
        val undoCallback = object : IUndoMediaTransferCallback.Stub() {
            override fun onUndoTriggered() {}
        }
        controllerSender.displayView(transferToThisDeviceSucceeded(undoCallback))

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(state)

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
        controllerSender.displayView(almostCloseToStartCast())
        controllerSender.displayView(transferToReceiverTriggered())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.VISIBLE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferSucceeded_loadingIconDisappears() {
        controllerSender.displayView(transferToReceiverTriggered())
        controllerSender.displayView(transferToReceiverSucceeded())

        assertThat(getChipView().getLoadingIconVisibility()).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferSucceeded_undoButtonAppears() {
        controllerSender.displayView(transferToReceiverTriggered())
        controllerSender.displayView(
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
        controllerSender.displayView(transferToReceiverSucceeded())
        controllerSender.displayView(almostCloseToStartCast())

        assertThat(getChipView().getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun changeFromTransferTriggeredToTransferFailed_failureIconAppears() {
        controllerSender.displayView(transferToReceiverTriggered())
        controllerSender.displayView(transferToReceiverFailed())

        assertThat(getChipView().getFailureIcon().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun transferToReceiverTriggeredThenRemoveView_viewStillDisplayed() {
        controllerSender.displayView(transferToReceiverTriggered())
        fakeClock.advanceTime(1000L)

        controllerSender.removeView("fakeRemovalReason")
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToReceiverTriggeredThenFarFromReceiver_viewStillDisplayed() {
        controllerSender.displayView(transferToReceiverTriggered())

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
    fun transferToReceiverTriggeredThenRemoveView_eventuallyTimesOut() {
        controllerSender.displayView(transferToReceiverTriggered())

        controllerSender.removeView("fakeRemovalReason")
        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenRemoveView_viewStillDisplayed() {
        controllerSender.displayView(transferToThisDeviceTriggered())
        fakeClock.advanceTime(1000L)

        controllerSender.removeView("fakeRemovalReason")
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenRemoveView_eventuallyTimesOut() {
        controllerSender.displayView(transferToThisDeviceTriggered())

        controllerSender.removeView("fakeRemovalReason")
        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenFarFromReceiver_viewStillDisplayed() {
        controllerSender.displayView(transferToThisDeviceTriggered())

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
    fun transferToReceiverSucceededThenRemoveView_viewStillDisplayed() {
        controllerSender.displayView(transferToReceiverSucceeded())

        controllerSender.removeView("fakeRemovalReason")
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToReceiverSucceededThenRemoveView_eventuallyTimesOut() {
        controllerSender.displayView(transferToReceiverSucceeded())

        controllerSender.removeView("fakeRemovalReason")
        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToReceiverSucceededThenFarFromReceiver_viewStillDisplayed() {
        controllerSender.displayView(transferToReceiverSucceeded())

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
    fun transferToThisDeviceSucceededThenRemoveView_viewStillDisplayed() {
        controllerSender.displayView(transferToThisDeviceSucceeded())

        controllerSender.removeView("fakeRemovalReason")
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    @Test
    fun transferToThisDeviceSucceededThenRemoveView_eventuallyTimesOut() {
        controllerSender.displayView(transferToThisDeviceSucceeded())

        controllerSender.removeView("fakeRemovalReason")
        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceSucceededThenFarFromReceiver_viewStillDisplayed() {
        controllerSender.displayView(transferToThisDeviceSucceeded())

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())
    }

    private fun ViewGroup.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)

    private fun ViewGroup.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun ViewGroup.getLoadingIconVisibility(): Int =
        this.requireViewById<View>(R.id.loading).visibility

    private fun ViewGroup.getUndoButton(): View = this.requireViewById(R.id.undo)

    private fun ViewGroup.getFailureIcon(): View = this.requireViewById(R.id.failure_icon)

    private fun getChipView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
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
private const val TIMEOUT = 10000

private val routeInfo = MediaRoute2Info.Builder("id", OTHER_DEVICE_NAME)
    .addFeature("feature")
    .setClientPackageName(PACKAGE_NAME)
    .build()
