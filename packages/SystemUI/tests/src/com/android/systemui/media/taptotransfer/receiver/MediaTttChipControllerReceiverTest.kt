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

package com.android.systemui.media.taptotransfer.receiver

import android.app.StatusBarManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.media.MediaRoute2Info
import android.os.Handler
import android.os.PowerManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.taptotransfer.MediaTttFlags
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.temporarydisplay.TemporaryViewUiEventLogger
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.view.ViewUtil
import com.android.systemui.util.wakelock.WakeLockFake
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
class MediaTttChipControllerReceiverTest : SysuiTestCase() {
    private lateinit var controllerReceiver: MediaTttChipControllerReceiver

    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var applicationInfo: ApplicationInfo
    @Mock
    private lateinit var logger: MediaTttReceiverLogger
    @Mock
    private lateinit var accessibilityManager: AccessibilityManager
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var dumpManager: DumpManager
    @Mock
    private lateinit var mediaTttFlags: MediaTttFlags
    @Mock
    private lateinit var powerManager: PowerManager
    @Mock
    private lateinit var viewUtil: ViewUtil
    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var commandQueue: CommandQueue
    @Mock
    private lateinit var rippleController: MediaTttReceiverRippleController
    private lateinit var commandQueueCallback: CommandQueue.Callbacks
    private lateinit var fakeAppIconDrawable: Drawable
    private lateinit var uiEventLoggerFake: UiEventLoggerFake
    private lateinit var receiverUiEventLogger: MediaTttReceiverUiEventLogger
    private lateinit var temporaryViewUiEventLogger: TemporaryViewUiEventLogger
    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor
    private lateinit var fakeWakeLockBuilder: WakeLockFake.Builder
    private lateinit var fakeWakeLock: WakeLockFake

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(mediaTttFlags.isMediaTttEnabled()).thenReturn(true)

        fakeAppIconDrawable = context.getDrawable(R.drawable.ic_cake)!!
        whenever(packageManager.getApplicationIcon(PACKAGE_NAME)).thenReturn(fakeAppIconDrawable)
        whenever(applicationInfo.loadLabel(packageManager)).thenReturn(APP_NAME)
        whenever(packageManager.getApplicationInfo(
            eq(PACKAGE_NAME), any<PackageManager.ApplicationInfoFlags>()
        )).thenReturn(applicationInfo)
        context.setMockPackageManager(packageManager)

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        uiEventLoggerFake = UiEventLoggerFake()
        receiverUiEventLogger = MediaTttReceiverUiEventLogger(uiEventLoggerFake)
        temporaryViewUiEventLogger = TemporaryViewUiEventLogger(uiEventLoggerFake)

        fakeWakeLock = WakeLockFake()
        fakeWakeLockBuilder = WakeLockFake.Builder(context)
        fakeWakeLockBuilder.setWakeLock(fakeWakeLock)

        controllerReceiver = FakeMediaTttChipControllerReceiver(
            commandQueue,
            context,
            logger,
            windowManager,
            fakeExecutor,
            accessibilityManager,
            configurationController,
            dumpManager,
            powerManager,
            Handler.getMain(),
            mediaTttFlags,
            receiverUiEventLogger,
            viewUtil,
            fakeWakeLockBuilder,
            fakeClock,
            rippleController,
            temporaryViewUiEventLogger,
        )
        controllerReceiver.start()

        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        commandQueueCallback = callbackCaptor.value!!
    }

    @Test
    fun commandQueueCallback_flagOff_noCallbackAdded() {
        reset(commandQueue)
        whenever(mediaTttFlags.isMediaTttEnabled()).thenReturn(false)

        controllerReceiver = MediaTttChipControllerReceiver(
            commandQueue,
            context,
            logger,
            windowManager,
            FakeExecutor(FakeSystemClock()),
            accessibilityManager,
            configurationController,
            dumpManager,
            powerManager,
            Handler.getMain(),
            mediaTttFlags,
            receiverUiEventLogger,
            viewUtil,
            fakeWakeLockBuilder,
            fakeClock,
            rippleController,
            temporaryViewUiEventLogger,
        )
        controllerReceiver.start()

        verify(commandQueue, never()).addCallback(any())
    }

    @Test
    fun commandQueueCallback_closeToSender_triggersChip() {
        val appName = "FakeAppName"
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            /* appIcon= */ null,
            appName
        )

        assertThat(getChipView().getAppIconView().contentDescription).isEqualTo(appName)
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_CLOSE_TO_SENDER.id
        )
        assertThat(uiEventLoggerFake.logs[0].instanceId).isNotNull()
    }

    @Test
    fun commandQueueCallback_farFromSender_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
            routeInfo,
            null,
            null
        )

        verify(windowManager, never()).addView(any(), any())
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
            MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_FAR_FROM_SENDER.id
        )
        assertThat(uiEventLoggerFake.logs[0].instanceId).isNotNull()
    }

    @Test
    fun commandQueueCallback_transferToReceiverSucceeded_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
                StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
                routeInfo,
                null,
                null
        )

        verify(windowManager, never()).addView(any(), any())
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
                MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_TRANSFER_TO_RECEIVER_SUCCEEDED.id
        )
        assertThat(uiEventLoggerFake.logs[0].instanceId).isNotNull()
    }

    @Test
    fun commandQueueCallback_transferToReceiverFailed_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
                StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_FAILED,
                routeInfo,
                null,
                null
        )

        verify(windowManager, never()).addView(any(), any())
        assertThat(uiEventLoggerFake.eventId(0)).isEqualTo(
                MediaTttReceiverUiEvents.MEDIA_TTT_RECEIVER_TRANSFER_TO_RECEIVER_FAILED.id
        )
        assertThat(uiEventLoggerFake.logs[0].instanceId).isNotNull()
    }

    @Test
    fun commandQueueCallback_closeThenFar_chipShownThenHidden() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            null,
            null
        )

        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
            routeInfo,
            null,
            null
        )

        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        verify(windowManager).removeView(viewCaptor.value)
    }

    @Test
    fun commandQueueCallback_closeThenSucceeded_chipShownThenHidden() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            null,
            null
        )

        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null,
            null
        )

        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        verify(windowManager).removeView(viewCaptor.value)
    }

    @Test
    fun commandQueueCallback_closeThenSucceeded_sameViewInstanceId() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            null,
            null
        )

        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_SUCCEEDED,
            routeInfo,
            null,
            null
        )

        assertThat(uiEventLoggerFake[0].instanceId).isEqualTo(uiEventLoggerFake[1].instanceId)
    }

    @Test
    fun commandQueueCallback_closeThenFailed_chipShownThenHidden() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            null,
            null
        )

        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_TRANSFER_TO_RECEIVER_FAILED,
            routeInfo,
            null,
            null
        )

        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        verify(windowManager).removeView(viewCaptor.value)
    }

    @Test
    fun commandQueueCallback_closeThenFar_wakeLockAcquiredThenReleased() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
                StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
                routeInfo,
                null,
                null
        )

        assertThat(fakeWakeLock.isHeld).isTrue()

        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
                StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
                routeInfo,
                null,
                null
        )

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun commandQueueCallback_closeThenFar_wakeLockNeverAcquired() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
                StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_FAR_FROM_SENDER,
                routeInfo,
                null,
                null
        )

        assertThat(fakeWakeLock.isHeld).isFalse()
    }

    @Test
    fun receivesNewStateFromCommandQueue_isLogged() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_RECEIVER_STATE_CLOSE_TO_SENDER,
            routeInfo,
            null,
            null
        )

        verify(logger).logStateChange(any(), any(), any())
    }

    @Test
    fun updateView_noOverrides_usesInfoFromAppIcon() {
        controllerReceiver.displayView(
            ChipReceiverInfo(
                routeInfo,
                appIconDrawableOverride = null,
                appNameOverride = null,
                id = "id",
                instanceId = InstanceId.fakeInstanceId(0),
            )
        )

        val view = getChipView()
        assertThat(view.getAppIconView().drawable).isEqualTo(fakeAppIconDrawable)
        assertThat(view.getAppIconView().contentDescription)
            .isEqualTo(context.getString(
                R.string.media_transfer_receiver_content_description_with_app_name,
                APP_NAME,
            ))
    }

    @Test
    fun updateView_appIconOverride_usesOverride() {
        val drawableOverride = context.getDrawable(R.drawable.ic_celebration)!!

        controllerReceiver.displayView(
            ChipReceiverInfo(
                routeInfo,
                drawableOverride,
                appNameOverride = null,
                id = "id",
                instanceId = InstanceId.fakeInstanceId(0),
            )
        )

        val view = getChipView()
        assertThat(view.getAppIconView().drawable).isEqualTo(drawableOverride)
    }

    @Test
    fun updateView_appNameOverride_usesOverride() {
        val appNameOverride = "Sweet New App"

        controllerReceiver.displayView(
            ChipReceiverInfo(
                routeInfo,
                appIconDrawableOverride = null,
                appNameOverride,
                id = "id",
                instanceId = InstanceId.fakeInstanceId(0),
            )
        )

        val view = getChipView()
        assertThat(view.getAppIconView().contentDescription).isEqualTo(appNameOverride)
    }

    @Test
    fun updateView_isAppIcon_usesAppIconPadding() {
        controllerReceiver.displayView(getChipReceiverInfo(packageName = PACKAGE_NAME))

        val chipView = getChipView()
        assertThat(chipView.getAppIconView().paddingLeft).isEqualTo(0)
        assertThat(chipView.getAppIconView().paddingRight).isEqualTo(0)
        assertThat(chipView.getAppIconView().paddingTop).isEqualTo(0)
        assertThat(chipView.getAppIconView().paddingBottom).isEqualTo(0)
    }

    @Test
    fun updateView_notAppIcon_usesGenericIconPadding() {
        controllerReceiver.displayView(getChipReceiverInfo(packageName = null))

        val chipView = getChipView()
        val expectedPadding =
            context.resources.getDimensionPixelSize(R.dimen.media_ttt_generic_icon_padding)
        assertThat(chipView.getAppIconView().paddingLeft).isEqualTo(expectedPadding)
        assertThat(chipView.getAppIconView().paddingRight).isEqualTo(expectedPadding)
        assertThat(chipView.getAppIconView().paddingTop).isEqualTo(expectedPadding)
        assertThat(chipView.getAppIconView().paddingBottom).isEqualTo(expectedPadding)
    }

    @Test
    fun commandQueueCallback_invalidStateParam_noChipShown() {
        commandQueueCallback.updateMediaTapToTransferReceiverDisplay(
            StatusBarManager.MEDIA_TRANSFER_SENDER_STATE_TRANSFER_TO_THIS_DEVICE_SUCCEEDED,
            routeInfo,
            null,
            APP_NAME
        )

        verify(windowManager, never()).addView(any(), any())
    }

    private fun getChipView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    private fun getChipReceiverInfo(packageName: String?): ChipReceiverInfo {
        val routeInfo = MediaRoute2Info.Builder("id", "Test route name")
            .addFeature("feature")
            .setClientPackageName(packageName)
            .build()
        return ChipReceiverInfo(
            routeInfo,
            null,
            null,
            id = "id",
            instanceId = InstanceId.fakeInstanceId(0),
        )
    }

    private fun ViewGroup.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)
}

private const val APP_NAME = "Fake app name"
private const val PACKAGE_NAME = "com.android.systemui"

private val routeInfo = MediaRoute2Info.Builder("id", "Test route name")
    .addFeature("feature")
    .setClientPackageName(PACKAGE_NAME)
    .build()
