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

package com.android.systemui.media.taptotransfer.common

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.gesture.TapGestureDetector
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.view.ViewUtil
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
class MediaTttChipControllerCommonTest : SysuiTestCase() {
    private lateinit var controllerCommon: MediaTttChipControllerCommon<ChipInfo>

    private lateinit var fakeClock: FakeSystemClock
    private lateinit var fakeExecutor: FakeExecutor

    private lateinit var appIconFromPackageName: Drawable
    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var applicationInfo: ApplicationInfo
    @Mock
    private lateinit var logger: MediaTttLogger
    @Mock
    private lateinit var windowManager: WindowManager
    @Mock
    private lateinit var viewUtil: ViewUtil
    @Mock
    private lateinit var tapGestureDetector: TapGestureDetector
    @Mock
    private lateinit var powerManager: PowerManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        appIconFromPackageName = context.getDrawable(R.drawable.ic_cake)!!
        whenever(packageManager.getApplicationIcon(PACKAGE_NAME)).thenReturn(appIconFromPackageName)
        whenever(applicationInfo.loadLabel(packageManager)).thenReturn(APP_NAME)
        whenever(packageManager.getApplicationInfo(
            any(), any<PackageManager.ApplicationInfoFlags>()
        )).thenThrow(PackageManager.NameNotFoundException())
        whenever(packageManager.getApplicationInfo(
            eq(PACKAGE_NAME), any<PackageManager.ApplicationInfoFlags>()
        )).thenReturn(applicationInfo)
        context.setMockPackageManager(packageManager)

        fakeClock = FakeSystemClock()
        fakeExecutor = FakeExecutor(fakeClock)

        controllerCommon = TestControllerCommon(
            context, logger, windowManager, viewUtil, fakeExecutor, tapGestureDetector, powerManager
        )
    }

    @Test
    fun displayChip_chipAddedAndGestureDetectionStartedAndScreenOn() {
        controllerCommon.displayChip(getState())

        verify(windowManager).addView(any(), any())
        verify(tapGestureDetector).addOnGestureDetectedCallback(any(), any())
        verify(powerManager).wakeUp(any(), any(), any())
    }

    @Test
    fun displayChip_twice_chipAndGestureDetectionNotAddedTwice() {
        controllerCommon.displayChip(getState())
        reset(windowManager)
        reset(tapGestureDetector)

        controllerCommon.displayChip(getState())
        verify(windowManager, never()).addView(any(), any())
        verify(tapGestureDetector, never()).addOnGestureDetectedCallback(any(), any())
    }

    @Test
    fun displayChip_chipDoesNotDisappearsBeforeTimeout() {
        val state = getState()
        controllerCommon.displayChip(state)
        reset(windowManager)

        fakeClock.advanceTime(TIMEOUT_MS - 1)

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun displayChip_chipDisappearsAfterTimeout() {
        val state = getState()
        controllerCommon.displayChip(state)
        reset(windowManager)

        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun displayChip_calledAgainBeforeTimeout_timeoutReset() {
        // First, display the chip
        val state = getState()
        controllerCommon.displayChip(state)

        // After some time, re-display the chip
        val waitTime = 1000L
        fakeClock.advanceTime(waitTime)
        controllerCommon.displayChip(getState())

        // Wait until the timeout for the first display would've happened
        fakeClock.advanceTime(TIMEOUT_MS - waitTime + 1)

        // Verify we didn't hide the chip
        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun displayChip_calledAgainBeforeTimeout_eventuallyTimesOut() {
        // First, display the chip
        val state = getState()
        controllerCommon.displayChip(state)

        // After some time, re-display the chip
        fakeClock.advanceTime(1000L)
        controllerCommon.displayChip(getState())

        // Ensure we still hide the chip eventually
        fakeClock.advanceTime(TIMEOUT_MS + 1)

        verify(windowManager).removeView(any())
    }

    @Test
    fun removeChip_chipRemovedAndGestureDetectionStoppedAndRemovalLogged() {
        // First, add the chip
        controllerCommon.displayChip(getState())

        // Then, remove it
        val reason = "test reason"
        controllerCommon.removeChip(reason)

        verify(windowManager).removeView(any())
        verify(tapGestureDetector).removeOnGestureDetectedCallback(any())
        verify(logger).logChipRemoval(reason)
    }

    @Test
    fun removeChip_noAdd_viewNotRemoved() {
        controllerCommon.removeChip("reason")

        verify(windowManager, never()).removeView(any())
    }

    @Test
    fun setIcon_nullAppIconDrawableAndNullPackageName_stillHasIcon() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(chipView, appPackageName = null, appIconDrawableOverride = null)

        assertThat(chipView.getAppIconView().drawable).isNotNull()
    }

    @Test
    fun setIcon_nullAppIconDrawableAndInvalidPackageName_stillHasIcon() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(
            chipView, appPackageName = "fakePackageName", appIconDrawableOverride = null
        )

        assertThat(chipView.getAppIconView().drawable).isNotNull()
    }

    @Test
    fun setIcon_nullAppIconDrawable_iconIsFromPackageName() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(chipView, PACKAGE_NAME, appIconDrawableOverride = null, null)

        assertThat(chipView.getAppIconView().drawable).isEqualTo(appIconFromPackageName)
    }

    @Test
    fun setIcon_hasAppIconDrawable_iconIsDrawable() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        val drawable = context.getDrawable(R.drawable.ic_alarm)!!
        controllerCommon.setIcon(chipView, PACKAGE_NAME, drawable, null)

        assertThat(chipView.getAppIconView().drawable).isEqualTo(drawable)
    }

    @Test
    fun setIcon_nullAppNameAndNullPackageName_stillHasContentDescription() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(chipView, appPackageName = null, appNameOverride = null)

        assertThat(chipView.getAppIconView().contentDescription.toString()).isNotEmpty()
    }

    @Test
    fun setIcon_nullAppNameAndInvalidPackageName_stillHasContentDescription() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(
            chipView, appPackageName = "fakePackageName", appNameOverride = null
        )

        assertThat(chipView.getAppIconView().contentDescription.toString()).isNotEmpty()
    }

    @Test
    fun setIcon_nullAppName_iconContentDescriptionIsFromPackageName() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(chipView, PACKAGE_NAME, null, appNameOverride = null)

        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(APP_NAME)
    }

    @Test
    fun setIcon_hasAppName_iconContentDescriptionIsAppNameOverride() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        val appName = "Override App Name"
        controllerCommon.setIcon(chipView, PACKAGE_NAME, null, appName)

        assertThat(chipView.getAppIconView().contentDescription).isEqualTo(appName)
    }

    @Test
    fun setIcon_iconSizeMatchesGetIconSize() {
        controllerCommon.displayChip(getState())
        val chipView = getChipView()

        controllerCommon.setIcon(chipView, PACKAGE_NAME)
        chipView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        assertThat(chipView.getAppIconView().measuredWidth).isEqualTo(ICON_SIZE)
        assertThat(chipView.getAppIconView().measuredHeight).isEqualTo(ICON_SIZE)
    }

    @Test
    fun tapGestureDetected_outsideViewBounds_viewHidden() {
        controllerCommon.displayChip(getState())
        whenever(viewUtil.touchIsWithinView(any(), any(), any())).thenReturn(false)
        val gestureCallbackCaptor = argumentCaptor<(MotionEvent) -> Unit>()
        verify(tapGestureDetector).addOnGestureDetectedCallback(
            any(), capture(gestureCallbackCaptor)
        )
        val callback = gestureCallbackCaptor.value!!

        callback.invoke(
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        )

        verify(windowManager).removeView(any())
    }

    @Test
    fun tapGestureDetected_insideViewBounds_viewNotHidden() {
        controllerCommon.displayChip(getState())
        whenever(viewUtil.touchIsWithinView(any(), any(), any())).thenReturn(true)
        val gestureCallbackCaptor = argumentCaptor<(MotionEvent) -> Unit>()
        verify(tapGestureDetector).addOnGestureDetectedCallback(
            any(), capture(gestureCallbackCaptor)
        )
        val callback = gestureCallbackCaptor.value!!

        callback.invoke(
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        )

        verify(windowManager, never()).removeView(any())
    }

    private fun getState() = ChipInfo()

    private fun getChipView(): ViewGroup {
        val viewCaptor = ArgumentCaptor.forClass(View::class.java)
        verify(windowManager).addView(viewCaptor.capture(), any())
        return viewCaptor.value as ViewGroup
    }

    private fun ViewGroup.getAppIconView() = this.requireViewById<ImageView>(R.id.app_icon)

    inner class TestControllerCommon(
        context: Context,
        logger: MediaTttLogger,
        windowManager: WindowManager,
        viewUtil: ViewUtil,
        @Main mainExecutor: DelayableExecutor,
        tapGestureDetector: TapGestureDetector,
        powerManager: PowerManager
    ) : MediaTttChipControllerCommon<ChipInfo>(
        context,
        logger,
        windowManager,
        viewUtil,
        mainExecutor,
        tapGestureDetector,
        powerManager,
        R.layout.media_ttt_chip
    ) {
        override fun updateChipView(chipInfo: ChipInfo, currentChipView: ViewGroup) {

        }

        override fun getIconSize(isAppIcon: Boolean): Int? = ICON_SIZE
    }

    inner class ChipInfo : ChipInfoCommon {
        override fun getTimeoutMs() = TIMEOUT_MS
    }
}

private const val PACKAGE_NAME = "com.android.systemui"
private const val APP_NAME = "Fake App Name"
private const val TIMEOUT_MS = 10000L
private const val ICON_SIZE = 47