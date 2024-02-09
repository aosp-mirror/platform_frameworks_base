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
import android.testing.TestableLooper.RunWithLooper
import android.view.DisplayCutout
import android.view.DisplayShape
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PrivacyIndicatorBounds
import android.view.RoundedCorners
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.Gefingerpoken
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

@SmallTest
@RunWithLooper(setAsMainLooper = true)
class PhoneStatusBarViewTest : SysuiTestCase() {

    private lateinit var view: PhoneStatusBarView

    private val contentInsetsProvider = mock<StatusBarContentInsetsProvider>()
    private val windowController = mock<StatusBarWindowController>()

    @Before
    fun setUp() {
        mDependency.injectTestDependency(
            StatusBarContentInsetsProvider::class.java,
            contentInsetsProvider
        )
        mDependency.injectTestDependency(DarkIconDispatcher::class.java, mock<DarkIconDispatcher>())
        mDependency.injectTestDependency(StatusBarWindowController::class.java, windowController)
        view = spy(createStatusBarView())
        whenever(view.rootWindowInsets).thenReturn(emptyWindowInsets())
        whenever(contentInsetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(Insets.NONE)
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
    fun onInterceptTouchEvent_listenerNotified() {
        val handler = TestTouchEventHandler()
        view.setTouchEventHandler(handler)

        val event = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
        view.onInterceptTouchEvent(event)

        assertThat(handler.lastInterceptEvent).isEqualTo(event)
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
    fun onAttachedToWindow_flagEnabled_updatesWindowHeight() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TRUNCATED_STATUS_BAR_ICONS_FIX)

        view.onAttachedToWindow()

        verify(windowController).refreshStatusBarHeight()
    }

    @Test
    fun onAttachedToWindow_flagDisabled_doesNotUpdateWindowHeight() {
        mSetFlagsRule.disableFlags(Flags.FLAG_TRUNCATED_STATUS_BAR_ICONS_FIX)

        view.onAttachedToWindow()

        verify(windowController, never()).refreshStatusBarHeight()
    }

    @Test
    fun onConfigurationChanged_flagEnabled_updatesWindowHeight() {
        mSetFlagsRule.enableFlags(Flags.FLAG_TRUNCATED_STATUS_BAR_ICONS_FIX)

        view.onConfigurationChanged(Configuration())

        verify(windowController).refreshStatusBarHeight()
    }

    @Test
    fun onConfigurationChanged_flagDisabled_doesNotUpdateWindowHeight() {
        mSetFlagsRule.disableFlags(Flags.FLAG_TRUNCATED_STATUS_BAR_ICONS_FIX)

        view.onConfigurationChanged(Configuration())

        verify(windowController, never()).refreshStatusBarHeight()
    }

    @Test
    fun onAttachedToWindow_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left = */ 10, /* top = */ 20, /* right = */ 30, /* bottom = */ 40)
        whenever(contentInsetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(insets)

        view.onAttachedToWindow()

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onConfigurationChanged_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left = */ 40, /* top = */ 30, /* right = */ 20, /* bottom = */ 10)
        whenever(contentInsetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(insets)

        view.onConfigurationChanged(Configuration())

        assertThat(view.paddingLeft).isEqualTo(insets.left)
        assertThat(view.paddingTop).isEqualTo(insets.top)
        assertThat(view.paddingRight).isEqualTo(insets.right)
        assertThat(view.paddingBottom).isEqualTo(0)
    }

    @Test
    fun onApplyWindowInsets_updatesLeftTopRightPaddingsBasedOnInsets() {
        val insets = Insets.of(/* left = */ 90, /* top = */ 10, /* right = */ 45, /* bottom = */ 50)
        whenever(contentInsetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(insets)

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
            /* suppressScrimTypes = */ 0,
            /* displayCutout = */ DisplayCutout.NO_CUTOUT,
            /* roundedCorners = */ RoundedCorners.NO_ROUNDED_CORNERS,
            /* privacyIndicatorBounds = */ PrivacyIndicatorBounds(),
            /* displayShape = */ DisplayShape.NONE,
            /* compatInsetsTypes = */ 0,
            /* compatIgnoreVisibility = */ false
        )
}
