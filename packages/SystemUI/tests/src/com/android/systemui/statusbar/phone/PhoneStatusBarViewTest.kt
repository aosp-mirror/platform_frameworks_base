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

package com.android.systemui.statusbar.phone

import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Rect
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper.RunWithLooper
import android.view.DisplayCutout
import android.view.DisplayShape
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PrivacyIndicatorBounds
import android.view.RoundedCorners
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT
import com.android.systemui.Flags.FLAG_STATUS_BAR_SWIPE_OVER_CHIP
import com.android.systemui.Gefingerpoken
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@SmallTest
@RunWithLooper(setAsMainLooper = true)
class PhoneStatusBarViewTest : SysuiTestCase() {

    private lateinit var view: PhoneStatusBarView
    private val systemIconsContainer: View
        get() = view.requireViewById(R.id.system_icons)

    private val windowController = mock<StatusBarWindowController>()

    @Before
    fun setUp() {
        mDependency.injectTestDependency(StatusBarWindowController::class.java, windowController)
        context.ensureTestableResources()
        view = spy(createStatusBarView())
        whenever(view.rootWindowInsets).thenReturn(emptyWindowInsets())
    }

    @Test
    fun onTouchEvent_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onTouchEvent(event)

        assertThat(handler.lastEvent).isEqualTo(event)
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun onInterceptTouchEvent_flagOff_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onInterceptTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun onInterceptTouchEvent_flagOn_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onInterceptTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun onInterceptTouchEvent_listenerReturnsFalse_flagOff_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = false

        assertThat(view.onInterceptTouchEvent(event)).isFalse()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun onInterceptTouchEvent_listenerReturnsFalse_flagOn_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = false

        assertThat(view.onInterceptTouchEvent(event)).isFalse()
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun onInterceptTouchEvent_listenerReturnsTrue_flagOff_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = true

        assertThat(view.onInterceptTouchEvent(event)).isFalse()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_SWIPE_OVER_CHIP)
    fun onInterceptTouchEvent_listenerReturnsTrue_flagOn_viewReturnsTrue() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = true

        assertThat(view.onInterceptTouchEvent(event)).isTrue()
    }

    @Test
    fun onTouchEvent_listenerReturnsTrue_viewReturnsTrue() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = true

        assertThat(view.onTouchEvent(event)).isTrue()
    }

    @Test
    fun onTouchEvent_listenerReturnsFalse_viewReturnsFalse() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)
        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)

        handler.handleTouchReturnValue = false

        assertThat(view.onTouchEvent(event)).isFalse()
    }

    @Test
    fun onTouchEvent_noListener_noCrash() {
        view.onTouchEvent(MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0))
        // No assert needed, just testing no crash
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT)
    fun onAttachedToWindow_flagOff_updatesWindowHeight() {
        view.onAttachedToWindow()

        verify(windowController).refreshStatusBarHeight()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT)
    fun onAttachedToWindow_flagOn_doesNotUpdateWindowHeight() {
        view.onAttachedToWindow()

        verify(windowController, never()).refreshStatusBarHeight()
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT)
    fun onConfigurationChanged_flagOff_updatesWindowHeight() {
        view.onConfigurationChanged(Configuration())

        verify(windowController).refreshStatusBarHeight()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT)
    fun onConfigurationChanged_flagOn_doesNotUpdateWindowHeight() {
        view.onConfigurationChanged(Configuration())

        verify(windowController, never()).refreshStatusBarHeight()
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT)
    fun onConfigurationChanged_multipleCalls_flagOff_updatesWindowHeightMultipleTimes() {
        view.onConfigurationChanged(Configuration())
        view.onConfigurationChanged(Configuration())
        view.onConfigurationChanged(Configuration())
        view.onConfigurationChanged(Configuration())

        verify(windowController, times(4)).refreshStatusBarHeight()
    }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_STOP_UPDATING_WINDOW_HEIGHT)
    fun onConfigurationChanged_multipleCalls_flagOn_neverUpdatesWindowHeight() {
        view.onConfigurationChanged(Configuration())
        view.onConfigurationChanged(Configuration())
        view.onConfigurationChanged(Configuration())
        view.onConfigurationChanged(Configuration())

        verify(windowController, never()).refreshStatusBarHeight()
    }

    @Test
    fun onAttachedToWindow_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left= */ 10, /* top= */ 20, /* right= */ 30, /* bottom= */ 40)
        view.setInsetsFetcher { insets }

        view.onAttachedToWindow()

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onAttachedToWindow_noInsetsFetcher_noCrash() {
        // Don't call `PhoneStatusBarView.setInsetsFetcher`

        // WHEN the view is attached
        view.onAttachedToWindow()

        // THEN there's no crash, and the padding stays as it was
        assertThat(view.paddingLeft).isEqualTo(0)
        assertThat(view.paddingTop).isEqualTo(0)
        assertThat(view.paddingRight).isEqualTo(0)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onAttachedToWindow_thenGetsInsetsFetcher_insetsUpdated() {
        view.onAttachedToWindow()

        // WHEN the insets fetcher is set after the view is attached
        val insets = Insets.of(/* left= */ 10, /* top= */ 20, /* right= */ 30, /* bottom= */ 40)
        view.setInsetsFetcher { insets }

        // THEN the insets are updated
        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }


    @Test
    fun onConfigurationChanged_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        view.setInsetsFetcher { insets }

        view.onConfigurationChanged(Configuration())

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_noInsetsFetcher_noCrash() {
        // Don't call `PhoneStatusBarView.setInsetsFetcher`

        // WHEN the view is attached
        view.onConfigurationChanged(Configuration())

        // THEN there's no crash, and the padding stays as it was
        assertThat(view.paddingLeft).isEqualTo(0)
        assertThat(view.paddingTop).isEqualTo(0)
        assertThat(view.paddingRight).isEqualTo(0)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_noRelevantChange_doesNotUpdateInsets() {
        val previousInsets =
            Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        val newInsets = Insets.NONE

        var useNewInsets = false
        val insetsFetcher = PhoneStatusBarView.InsetsFetcher {
            if (useNewInsets) {
                newInsets
            } else {
                previousInsets
            }
        }
        view.setInsetsFetcher(insetsFetcher)

        context.orCreateTestableResources.overrideConfiguration(Configuration())
        view.onAttachedToWindow()

        useNewInsets = true
        view.onConfigurationChanged(Configuration())

        assertThat(view.paddingLeft).isEqualTo(previousInsets.left)
        assertThat(view.paddingTop).isEqualTo(previousInsets.top)
        assertThat(view.paddingRight).isEqualTo(previousInsets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_densityChanged_updatesInsets() {
        val previousInsets =
            Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        val newInsets = Insets.NONE

        var useNewInsets = false
        val insetsFetcher = PhoneStatusBarView.InsetsFetcher {
            if (useNewInsets) {
                newInsets
            } else {
                previousInsets
            }
        }
        view.setInsetsFetcher(insetsFetcher)

        val configuration = Configuration()
        configuration.densityDpi = 123
        context.orCreateTestableResources.overrideConfiguration(configuration)
        view.onAttachedToWindow()

        useNewInsets = true
        configuration.densityDpi = 456
        view.onConfigurationChanged(configuration)

        assertThat(view.paddingLeft).isEqualTo(newInsets.left)
        assertThat(view.paddingTop).isEqualTo(newInsets.top)
        assertThat(view.paddingRight).isEqualTo(newInsets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_fontScaleChanged_updatesInsets() {
        val previousInsets =
            Insets.of(/* left= */ 40, /* top= */ 30, /* right= */ 20, /* bottom= */ 10)
        val newInsets = Insets.NONE

        var useNewInsets = false
        val insetsFetcher = PhoneStatusBarView.InsetsFetcher {
            if (useNewInsets) {
                newInsets
            } else {
                previousInsets
            }
        }
        view.setInsetsFetcher(insetsFetcher)

        val configuration = Configuration()
        configuration.fontScale = 1f
        context.orCreateTestableResources.overrideConfiguration(configuration)
        view.onAttachedToWindow()

        useNewInsets = true
        configuration.fontScale = 2f
        view.onConfigurationChanged(configuration)

        assertThat(view.paddingLeft).isEqualTo(newInsets.left)
        assertThat(view.paddingTop).isEqualTo(newInsets.top)
        assertThat(view.paddingRight).isEqualTo(newInsets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_systemIconsHeightChanged_containerHeightIsUpdated() {
        val newHeight = 123456
        context.orCreateTestableResources.addOverride(
            R.dimen.status_bar_system_icons_height,
            newHeight
        )

        view.onConfigurationChanged(Configuration())

        assertThat(systemIconsContainer.layoutParams.height).isEqualTo(newHeight)
    }

    @Test
    fun onApplyWindowInsets_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left= */ 90, /* top= */ 10, /* right= */ 45, /* bottom= */ 50)
        view.setInsetsFetcher { insets }

        view.onApplyWindowInsets(WindowInsets(Rect()))

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    private class TestTouchEventHandler : Gefingerpoken {
        var lastInterceptEvent: MotionEvent? = null
        var lastEvent: MotionEvent? = null
        var handleTouchReturnValue: Boolean = false

        override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
            lastInterceptEvent = event
            return handleTouchReturnValue
        }

        override fun onTouchEvent(event: MotionEvent?): Boolean {
            lastEvent = event
            return handleTouchReturnValue
        }
    }

    private fun createStatusBarView() =
        LayoutInflater.from(context)
            .inflate(
                R.layout.status_bar,
                /* root= */ FrameLayout(context),
                /* attachToRoot = */ false
            ) as PhoneStatusBarView

    private fun emptyWindowInsets() =
        WindowInsets(
            /* typeInsetsMap = */ arrayOf(),
            /* typeMaxInsetsMap = */ arrayOf(),
            /* typeVisibilityMap = */ booleanArrayOf(),
            /* isRound = */ false,
            /* forceConsumingTypes = */ 0,
            /* forceConsumingOpaqueCaptionBar = */ false,
            /* suppressScrimTypes = */ 0,
            /* displayCutout = */ DisplayCutout.NO_CUTOUT,
            /* roundedCorners = */ RoundedCorners.NO_ROUNDED_CORNERS,
            /* privacyIndicatorBounds = */ PrivacyIndicatorBounds(),
            /* displayShape = */ DisplayShape.NONE,
            /* compatInsetsTypes = */ 0,
            /* compatIgnoreVisibility = */ false,
            /* typeBoundingRectsMap = */ arrayOf(),
            /* typeMaxBoundingRectsMap = */ arrayOf(),
            /* frameWidth = */ 0,
            /* frameHeight = */ 0
        )
}
