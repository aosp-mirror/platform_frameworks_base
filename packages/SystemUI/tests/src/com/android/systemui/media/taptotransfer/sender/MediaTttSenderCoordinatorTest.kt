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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaRoute2Info
import android.os.PowerManager
import android.os.VibrationEffect
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
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewDisplayController
import com.android.systemui.temporarydisplay.chipbar.ChipbarAnimator
import com.android.systemui.temporarydisplay.chipbar.ChipbarCoordinator
import com.android.systemui.temporarydisplay.chipbar.ChipbarLogger
import com.android.systemui.temporarydisplay.chipbar.SwipeChipbarAwayGestureHandler
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLockFake
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaTttSenderCoordinatorTest : SysuiTestCase() {

    // Note: This tests are a bit like integration tests because they use a real instance of
    //   [ChipbarCoordinator] and verify that the coordinator displays the correct view, based on
    //   the inputs from [MediaTttSenderCoordinator].

    private lateinit var underTest: MediaTttSenderCoordinator

    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var applicationInfo: ApplicationInfo
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var configurationController: ConfigurationController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var falsingCollector: FalsingCollector
    @Mock private lateinit var chipbarLogger: ChipbarLogger
    @Mock private lateinit var logger: MediaTttSenderLogger
    @Mock private lateinit var mediaTttFlags: MediaTttFlags
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var viewUtil: ViewUtil
    @Mock private lateinit var windowManager: WindowManager
    @Mock private lateinit var vibratorHelper: VibratorHelper
    @Mock private lateinit var swipeHandler: SwipeChipbarAwayGestureHandler
    private lateinit var fakeWakeLockBuilder: WakeLockFake.Builder
    private lateinit var fakeWakeLock: WakeLockFake
    private lateinit var chipbarCoordinator: ChipbarCoordinator
    private lateinit var commandQueueCallback: CommandQueue.Callbacks
    private lateinit var fakeAppIconDrawable: Drawable
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var uiEventLogger: MediaTttSenderUiEventLogger
    private val defaultTimeout = context.resources.getInteger(R.integer.heads_up_notification_decay)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(mediaTttFlags.isMediaTttEnabled()).thenReturn(true)
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenReturn(TIMEOUT)

        fakeAppIconDrawable = context.getDrawable(R.drawable.ic_cake)!!
        whenever(applicationInfo.loadLabel(packageManager)).thenReturn(APP_NAME)
        whenever(packageManager.getApplicationIcon(PACKAGE_NAME)).thenReturn(fakeAppIconDrawable)
        whenever(
                packageManager.getApplicationInfo(
                    eq(PACKAGE_NAME),
                    any<PackageManager.ApplicationInfoFlags>()
                )
            )
            .thenReturn(applicationInfo)
        context.setMockPackageManager(packageManager)

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        fakeWakeLock = WakeLockFake()
        fakeWakeLockBuilder = WakeLockFake.Builder(context)
        fakeWakeLockBuilder.setWakeLock(fakeWakeLock)

        uiEventLoggerFake = UiEventLoggerFake()
        uiEventLogger = MediaTttSenderUiEventLogger(uiEventLoggerFake)

        chipbarCoordinator =
            ChipbarCoordinator(
                context,
                chipbarLogger,
                windowManager,
                fakeExecutor,
                accessibilityManager,
                configurationController,
                dumpManager,
                powerManager,
                ChipbarAnimator(),
                falsingManager,
                falsingCollector,
                swipeHandler,
                viewUtil,
                vibratorHelper,
                fakeWakeLockBuilder,
                fakeClock,
            )
        chipbarCoordinator.start()

        underTest =
            MediaTttSenderCoordinator(
                chipbarCoordinator,
                commandQueue,
                context,
                dumpManager,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()

        setCommandQueueCallback()
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
                dumpManager,
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

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.ALMOST_CLOSE_TO_START_CAST.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_START_CAST.id)
        verify(vibratorHelper).vibrate(any<VibrationEffect>())
    }

    @Test
    fun commandQueueCallback_almostCloseToStartCast_deviceNameBlank_showsDefaultDeviceName() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfoWithBlankDeviceName,
            null,
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .contains(context.getString(R.string.media_ttt_default_device_type))
        assertThat(chipbarView.getChipText())
            .isNotEqualTo(ChipStateSender.ALMOST_CLOSE_TO_START_CAST.getExpectedStateText())
    }

    @Test
    fun commandQueueCallback_almostCloseToEndCast_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.ALMOST_CLOSE_TO_END_CAST.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_ALMOST_CLOSE_TO_END_CAST.id)
        verify(vibratorHelper).vibrate(any<VibrationEffect>())
    }

    @Test
    fun commandQueueCallback_transferToReceiverTriggered_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_TRIGGERED.id)
        verify(vibratorHelper).vibrate(any<VibrationEffect>())
    }

    @Test
    fun commandQueueCallback_transferToReceiverTriggered_deviceNameBlank_showsDefaultDeviceName() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfoWithBlankDeviceName,
            null,
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .contains(context.getString(R.string.media_ttt_default_device_type))
        assertThat(chipbarView.getChipText())
            .isNotEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.getExpectedStateText())
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceTriggered_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.GONE)
        assertThat(uiEventLoggerFake.eventId(0))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_TRIGGERED.id)
        verify(vibratorHelper).vibrate(any<VibrationEffect>())
    }

    @Test
    fun commandQueueCallback_transferToReceiverSucceeded_triggersCorrectChip() {
        displayReceiverTriggered()
        reset(vibratorHelper)
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_SUCCEEDED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        // Event index 1 since initially displaying the triggered chip would also log an event.
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_SUCCEEDED.id)
        verify(vibratorHelper, never()).vibrate(any<VibrationEffect>())
    }

    @Test
    fun transferToReceiverSucceeded_nullUndoCallback_noUndo() {
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToReceiverSucceeded_withUndoRunnable_undoVisible() {
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {}
            },
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getUndoButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun transferToReceiverSucceeded_undoButtonClick_switchesToTransferToThisDeviceTriggered() {
        var undoCallbackCalled = false
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {
                    undoCallbackCalled = true
                }
            },
        )

        getChipbarView().getUndoButton().performClick()

        // Event index 2 since initially displaying the triggered and succeeded chip would also log
        // events.
        assertThat(uiEventLoggerFake.eventId(2))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_RECEIVER_CLICKED.id)
        assertThat(undoCallbackCalled).isTrue()
        assertThat(getChipbarView().getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED.getExpectedStateText())
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceSucceeded_triggersCorrectChip() {
        displayThisDeviceTriggered()
        reset(vibratorHelper)
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_SUCCEEDED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        // Event index 1 since initially displaying the triggered chip would also log an event.
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_SUCCEEDED.id)
        verify(vibratorHelper, never()).vibrate(any<VibrationEffect>())
    }

    @Test
    fun transferToThisDeviceSucceeded_nullUndoCallback_noUndo() {
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun transferToThisDeviceSucceeded_withUndoRunnable_undoVisible() {
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {}
            },
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
        assertThat(chipbarView.getUndoButton().hasOnClickListeners()).isTrue()
    }

    @Test
    fun transferToThisDeviceSucceeded_undoButtonClick_switchesToTransferToThisDeviceTriggered() {
        var undoCallbackCalled = false
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {
                    undoCallbackCalled = true
                }
            },
        )

        getChipbarView().getUndoButton().performClick()

        // Event index 2 since initially displaying the triggered and succeeded chip would also log
        // events.
        assertThat(uiEventLoggerFake.eventId(2))
            .isEqualTo(
                MediaTttSenderUiEvents.MEDIA_TTT_SENDER_UNDO_TRANSFER_TO_THIS_DEVICE_CLICKED.id
            )
        assertThat(undoCallbackCalled).isTrue()
        assertThat(getChipbarView().getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.getExpectedStateText())
    }

    @Test
    fun commandQueueCallback_transferToReceiverFailed_triggersCorrectChip() {
        displayReceiverTriggered()
        reset(vibratorHelper)
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_FAILED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.VISIBLE)
        // Event index 1 since initially displaying the triggered chip would also log an event.
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_RECEIVER_FAILED.id)
        verify(vibratorHelper).vibrate(any<VibrationEffect>())
    }

    @Test
    fun commandQueueCallback_transferToThisDeviceFailed_triggersCorrectChip() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )
        reset(vibratorHelper)
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
            routeInfo,
            null
        )

        val chipbarView = getChipbarView()
        assertThat(chipbarView.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(chipbarView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_FAILED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getErrorIcon().visibility).isEqualTo(View.VISIBLE)
        // Event index 1 since initially displaying the triggered chip would also log an event.
        assertThat(uiEventLoggerFake.eventId(1))
            .isEqualTo(MediaTttSenderUiEvents.MEDIA_TTT_SENDER_TRANSFER_TO_THIS_DEVICE_FAILED.id)
        verify(vibratorHelper).vibrate(any<VibrationEffect>())
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
        verify(logger).logStateMapRemoval(eq(DEFAULT_ID), any())
    }

    @Test
    fun commandQueueCallback_almostCloseThenFarFromReceiver_wakeLockAcquiredThenReleased() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        assertThat(fakeWakeLock.isHeld).isTrue()

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun commandQueueCallback_FarFromReceiver_wakeLockNeverReleased() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun commandQueueCallback_invalidStateParam_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(100, routeInfo, null)

        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_receiverTriggeredThenAlmostStart_invalidTransitionLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_thisDeviceTriggeredThenAlmostEnd_invalidTransitionLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_receiverSucceededThenThisDeviceSucceeded_invalidTransitionLogged() {
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_thisDeviceSucceededThenReceiverSucceeded_invalidTransitionLogged() {
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_almostStartThenReceiverSucceeded_invalidTransitionLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_almostEndThenThisDeviceSucceeded_invalidTransitionLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_AlmostStartThenReceiverFailed_invalidTransitionLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    @Test
    fun commandQueueCallback_almostEndThenThisDeviceFailed_invalidTransitionLogged() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
            routeInfo,
            null
        )

        verify(logger).logInvalidStateTransitionError(any(), any())
        verify(windowManager, never()).addView(any(), any())
    }

    /** Regression test for b/266217596. */
    @Test
    fun toReceiver_triggeredThenFar_thenSucceeded_updatesToSucceeded() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null,
        )

        // WHEN a FAR command comes in
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null,
        )

        // THEN it is ignored and the chipbar is stilled displayed
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
        verify(windowManager, never()).removeView(any())

        // WHEN a SUCCEEDED command comes in
        val succeededRouteInfo =
            MediaRoute2Info.Builder(DEFAULT_ID, "Tablet Succeeded")
                .addFeature("feature")
                .setClientPackageName(PACKAGE_NAME)
                .build()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            succeededRouteInfo,
            /* undoCallback= */ object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {}
            },
        )

        // THEN it is *not* marked as an invalid transition and the chipbar updates to the succeeded
        // state. (The "invalid transition" would be FAR => SUCCEEDED.)
        assertThat(chipbarView.getChipText())
            .isEqualTo(
                ChipStateSender.TRANSFER_TO_RECEIVER_SUCCEEDED.getExpectedStateText(
                    "Tablet Succeeded"
                )
            )
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
    }

    /** Regression test for b/266217596. */
    @Test
    fun toThisDevice_triggeredThenFar_thenSucceeded_updatesToSucceeded() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null,
        )

        // WHEN a FAR command comes in
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null,
        )

        // THEN it is ignored and the chipbar is stilled displayed
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
        verify(windowManager, never()).removeView(any())

        // WHEN a SUCCEEDED command comes in
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            /* undoCallback= */ object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {}
            },
        )

        // THEN it is *not* marked as an invalid transition and the chipbar updates to the succeeded
        // state. (The "invalid transition" would be FAR => SUCCEEDED.)
        assertThat(chipbarView.getChipText())
            .isEqualTo(
                ChipStateSender.TRANSFER_TO_THIS_DEVICE_SUCCEEDED.getExpectedStateText(
                    "Tablet Succeeded"
                )
            )
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.GONE)
        assertThat(chipbarView.getUndoButton().visibility).isEqualTo(View.VISIBLE)
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
    fun transferToReceiverTriggeredThenFarFromReceiver_viewStillDisplayedButStillTimesOut() {
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

        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceTriggeredThenFarFromReceiver_viewStillDisplayedButDoesTimeOut() {
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

        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToReceiverSucceededThenFarFromReceiver_viewStillDisplayedButDoesTimeOut() {
        displayReceiverTriggered()
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

        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceSucceededThenFarFromReceiver_viewStillDisplayedButDoesTimeOut() {
        displayThisDeviceTriggered()
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

        fakeClock.advanceTime(TIMEOUT + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToReceiverSucceeded_thenUndo_thenFar_viewStillDisplayedButDoesTimeOut() {
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {}
            },
        )
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_SUCCEEDED.getExpectedStateText())

        // Because [MediaTttSenderCoordinator] internally creates the undo callback, we should
        // verify that the new state it triggers operates just like any other state.
        getChipbarView().getUndoButton().performClick()
        fakeExecutor.runAllReady()

        // Verify that the click updated us to the triggered state
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED.getExpectedStateText())

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        // Verify that we didn't remove the chipbar because it's in the triggered state
        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())

        fakeClock.advanceTime(TIMEOUT + 1L)

        // Verify we eventually remove the chipbar
        verify(windowManager).removeView(any())
    }

    @Test
    fun transferToThisDeviceSucceeded_thenUndo_thenFar_viewStillDisplayedButDoesTimeOut() {
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            object : IUndoMediaTransferCallback.Stub() {
                override fun onUndoTriggered() {}
            },
        )
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_THIS_DEVICE_SUCCEEDED.getExpectedStateText())

        // Because [MediaTttSenderCoordinator] internally creates the undo callback, we should
        // verify that the new state it triggers operates just like any other state.
        getChipbarView().getUndoButton().performClick()
        fakeExecutor.runAllReady()

        // Verify that the click updated us to the triggered state
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.getExpectedStateText())

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )
        fakeExecutor.runAllReady()

        // Verify that we didn't remove the chipbar because it's in the triggered state
        verify(windowManager, never()).removeView(any())
        verify(logger).logRemovalBypass(any(), any())

        fakeClock.advanceTime(TIMEOUT + 1L)

        // Verify we eventually remove the chipbar
        verify(windowManager).removeView(any())
    }

    @Test
    fun newState_viewListenerRegistered() {
        val mockChipbarCoordinator = mock<ChipbarCoordinator>()
        underTest =
            MediaTttSenderCoordinator(
                mockChipbarCoordinator,
                commandQueue,
                context,
                dumpManager,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()
        // Re-set the command queue callback since we've created a new [MediaTttSenderCoordinator]
        // with a new callback.
        setCommandQueueCallback()

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null,
        )

        verify(mockChipbarCoordinator).registerListener(any())
    }

    @Test
    fun onInfoPermanentlyRemoved_viewListenerUnregistered() {
        val mockChipbarCoordinator = mock<ChipbarCoordinator>()
        underTest =
            MediaTttSenderCoordinator(
                mockChipbarCoordinator,
                commandQueue,
                context,
                dumpManager,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()
        setCommandQueueCallback()

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null,
        )

        val listenerCaptor = argumentCaptor<TemporaryViewDisplayController.Listener>()
        verify(mockChipbarCoordinator).registerListener(capture(listenerCaptor))

        // WHEN the listener is notified that the view has been removed
        listenerCaptor.value.onInfoPermanentlyRemoved(DEFAULT_ID, "reason")

        // THEN the media coordinator unregisters the listener
        verify(logger).logStateMapRemoval(DEFAULT_ID, "reason")
        verify(mockChipbarCoordinator).unregisterListener(listenerCaptor.value)
    }

    @Test
    fun onInfoPermanentlyRemoved_wrongId_viewListenerNotUnregistered() {
        val mockChipbarCoordinator = mock<ChipbarCoordinator>()
        underTest =
            MediaTttSenderCoordinator(
                mockChipbarCoordinator,
                commandQueue,
                context,
                dumpManager,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()
        setCommandQueueCallback()

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null,
        )

        val listenerCaptor = argumentCaptor<TemporaryViewDisplayController.Listener>()
        verify(mockChipbarCoordinator).registerListener(capture(listenerCaptor))

        // WHEN the listener is notified that a different view has been removed
        listenerCaptor.value.onInfoPermanentlyRemoved("differentViewId", "reason")

        // THEN the media coordinator doesn't unregister the listener
        verify(mockChipbarCoordinator, never()).unregisterListener(listenerCaptor.value)
    }

    @Test
    fun farFromReceiverState_viewListenerUnregistered() {
        val mockChipbarCoordinator = mock<ChipbarCoordinator>()
        underTest =
            MediaTttSenderCoordinator(
                mockChipbarCoordinator,
                commandQueue,
                context,
                dumpManager,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()
        setCommandQueueCallback()

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null,
        )

        val listenerCaptor = argumentCaptor<TemporaryViewDisplayController.Listener>()
        verify(mockChipbarCoordinator).registerListener(capture(listenerCaptor))

        // WHEN we go to the FAR_FROM_RECEIVER state
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            routeInfo,
            null
        )

        // THEN the media coordinator unregisters the listener
        verify(mockChipbarCoordinator).unregisterListener(listenerCaptor.value)
    }

    @Test
    fun statesWithDifferentIds_onInfoPermanentlyRemovedForOneId_viewListenerNotUnregistered() {
        val mockChipbarCoordinator = mock<ChipbarCoordinator>()
        underTest =
            MediaTttSenderCoordinator(
                mockChipbarCoordinator,
                commandQueue,
                context,
                dumpManager,
                logger,
                mediaTttFlags,
                uiEventLogger,
            )
        underTest.start()
        setCommandQueueCallback()

        // WHEN there are two different media transfers with different IDs
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            MediaRoute2Info.Builder("route1", OTHER_DEVICE_NAME)
                .addFeature("feature")
                .setClientPackageName(PACKAGE_NAME)
                .build(),
            null,
        )
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            MediaRoute2Info.Builder("route2", OTHER_DEVICE_NAME)
                .addFeature("feature")
                .setClientPackageName(PACKAGE_NAME)
                .build(),
            null,
        )

        val listenerCaptor = argumentCaptor<TemporaryViewDisplayController.Listener>()
        verify(mockChipbarCoordinator, atLeast(1)).registerListener(capture(listenerCaptor))

        // THEN one of them is removed
        listenerCaptor.value.onInfoPermanentlyRemoved("route1", "reason")

        // THEN the media coordinator doesn't unregister the listener (since route2 is still active)
        verify(mockChipbarCoordinator, never()).unregisterListener(listenerCaptor.value)
        verify(logger).logStateMapRemoval("route1", "reason")
    }

    /** Regression test for b/266218672. */
    @Test
    fun twoIdsDisplayed_oldIdIsFar_viewStillDisplayed() {
        // WHEN there are two different media transfers with different IDs
        val route1 =
            MediaRoute2Info.Builder("route1", OTHER_DEVICE_NAME)
                .addFeature("feature")
                .setClientPackageName(PACKAGE_NAME)
                .build()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            route1,
            null,
        )
        verify(windowManager).addView(any(), any())
        reset(windowManager)

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            MediaRoute2Info.Builder("route2", "Route 2 name")
                .addFeature("feature")
                .setClientPackageName(PACKAGE_NAME)
                .build(),
            null,
        )
        val newView = getChipbarView()

        // WHEN there's a FAR event for the earlier one
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_FAR_FROM_RECEIVER,
            route1,
            null,
        )

        // THEN it's ignored and the more recent one is still displayed
        assertThat(newView.getChipText())
            .isEqualTo(
                ChipStateSender.ALMOST_CLOSE_TO_START_CAST.getExpectedStateText("Route 2 name")
            )
    }

    /** Regression test for b/266218672. */
    @Test
    fun receiverSucceededThenTimedOut_internalStateResetAndCanDisplayAlmostCloseToEnd() {
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null,
        )

        fakeClock.advanceTime(TIMEOUT + 1L)
        verify(windowManager).removeView(any())

        reset(windowManager)

        // WHEN we try to show ALMOST_CLOSE_TO_END
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_END_CAST,
            routeInfo,
            null,
        )

        // THEN it succeeds
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.ALMOST_CLOSE_TO_END_CAST.getExpectedStateText())
    }

    /** Regression test for b/266218672. */
    @Test
    fun receiverSucceededThenTimedOut_internalStateResetAndCanDisplayReceiverTriggered() {
        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null,
        )

        fakeClock.advanceTime(TIMEOUT + 1L)
        verify(windowManager).removeView(any())

        reset(windowManager)

        // WHEN we try to show RECEIVER_TRIGGERED
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null,
        )

        // THEN it succeeds
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.TRANSFER_TO_RECEIVER_TRIGGERED.getExpectedStateText())
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
    }

    /** Regression test for b/266218672. */
    @Test
    fun toThisDeviceSucceededThenTimedOut_internalStateResetAndCanDisplayAlmostCloseToStart() {
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null,
        )

        fakeClock.advanceTime(TIMEOUT + 1L)
        verify(windowManager).removeView(any())

        reset(windowManager)

        // WHEN we try to show ALMOST_CLOSE_TO_START
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null,
        )

        // THEN it succeeds
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(ChipStateSender.ALMOST_CLOSE_TO_START_CAST.getExpectedStateText())
    }

    /** Regression test for b/266218672. */
    @Test
    fun toThisDeviceSucceededThenTimedOut_internalStateResetAndCanDisplayThisDeviceTriggered() {
        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null,
        )

        fakeClock.advanceTime(TIMEOUT + 1L)
        verify(windowManager).removeView(any())

        reset(windowManager)

        // WHEN we try to show THIS_DEVICE_TRIGGERED
        val newRouteInfo =
            MediaRoute2Info.Builder(DEFAULT_ID, "New Name")
                .addFeature("feature")
                .setClientPackageName(PACKAGE_NAME)
                .build()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            newRouteInfo,
            null,
        )

        // THEN it succeeds
        val chipbarView = getChipbarView()
        assertThat(chipbarView.getChipText())
            .isEqualTo(
                ChipStateSender.TRANSFER_TO_THIS_DEVICE_TRIGGERED.getExpectedStateText("New Name")
            )
        assertThat(chipbarView.getLoadingIcon().visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun almostClose_hasLongTimeout_eventuallyTimesOut() {
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenAnswer {
            it.arguments[0]
        }

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_ALMOST_CLOSE_TO_START_CAST,
            routeInfo,
            null,
        )

        // WHEN the default timeout has passed
        fakeClock.advanceTime(defaultTimeout + 1L)

        // THEN the view is still on-screen because it has a long timeout
        verify(windowManager, never()).removeView(any())

        // WHEN a very long amount of time has passed
        fakeClock.advanceTime(5L * defaultTimeout)

        // THEN the view does time out
        verify(windowManager).removeView(any())
    }

    @Test
    fun loading_hasLongTimeout_eventuallyTimesOut() {
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenAnswer {
            it.arguments[0]
        }

        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null,
        )

        // WHEN the default timeout has passed
        fakeClock.advanceTime(defaultTimeout + 1L)

        // THEN the view is still on-screen because it has a long timeout
        verify(windowManager, never()).removeView(any())

        // WHEN a very long amount of time has passed
        fakeClock.advanceTime(5L * defaultTimeout)

        // THEN the view does time out
        verify(windowManager).removeView(any())
    }

    @Test
    fun succeeded_hasDefaultTimeout() {
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenAnswer {
            it.arguments[0]
        }

        displayReceiverTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null,
        )

        fakeClock.advanceTime(defaultTimeout + 1L)

        verify(windowManager).removeView(any())
    }

    @Test
    fun failed_hasDefaultTimeout() {
        whenever(accessibilityManager.getRecommendedTimeoutMillis(any(), any())).thenAnswer {
            it.arguments[0]
        }

        displayThisDeviceTriggered()
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_FAILED,
            routeInfo,
            null,
        )

        fakeClock.advanceTime(defaultTimeout + 1L)

        verify(windowManager).removeView(any())
    }

    private fun getChipbarView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    private fun ViewGroup.getAppIconView() = this.requireViewById<ImageView>(R.id.start_icon)

    private fun ViewGroup.getChipText(): String =
        (this.requireViewById<TextView>(R.id.text)).text as String

    private fun ViewGroup.getLoadingIcon(): View = this.requireViewById(R.id.loading)

    private fun ViewGroup.getErrorIcon(): View = this.requireViewById(R.id.error)

    private fun ViewGroup.getUndoButton(): View = this.requireViewById(R.id.end_button)

    private fun ChipStateSender.getExpectedStateText(
        otherDeviceName: String = OTHER_DEVICE_NAME,
    ): String? {
        return this.getChipTextString(context, otherDeviceName).loadText(context)
    }

    // display receiver triggered state helper method to make sure we start from a valid state
    // transition (FAR_FROM_RECEIVER -> TRANSFER_TO_RECEIVER_TRIGGERED).
    private fun displayReceiverTriggered() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_RECEIVER_TRIGGERED,
            routeInfo,
            null
        )
    }

    // display this device triggered state helper method to make sure we start from a valid state
    // transition (FAR_FROM_RECEIVER -> TRANSFER_TO_THIS_DEVICE_TRIGGERED).
    private fun displayThisDeviceTriggered() {
        commandQueueCallback.updateMediaTapToTransferSenderDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_TRIGGERED,
            routeInfo,
            null
        )
    }

    private fun setCommandQueueCallback() {
        val callbackCaptor = argumentCaptor<CommandQueue.Callbacks>()
        verify(commandQueue).addCallback(capture(callbackCaptor))
        commandQueueCallback = callbackCaptor.value
        reset(commandQueue)
    }
}

private const val DEFAULT_ID = "defaultId"
private const val APP_NAME = "Fake app name"
private const val OTHER_DEVICE_NAME = "My Tablet"
private const val BLANK_DEVICE_NAME = " "
private const val PACKAGE_NAME = "com.android.systemui"
private const val TIMEOUT = 10000

private val routeInfo =
    MediaRoute2Info.Builder(DEFAULT_ID, OTHER_DEVICE_NAME)
        .addFeature("feature")
        .setClientPackageName(PACKAGE_NAME)
        .build()

private val routeInfoWithBlankDeviceName =
    MediaRoute2Info.Builder(DEFAULT_ID, BLANK_DEVICE_NAME)
        .addFeature("feature")
        .setClientPackageName(PACKAGE_NAME)
        .build()
