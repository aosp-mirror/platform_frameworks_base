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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.PaintDrawable
import android.os.SystemClock
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.testing.ViewUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroupOverlay
import android.widget.LinearLayout
import androidx.annotation.ColorInt
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher.DarkChange
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class StatusOverlayHoverListenerTest : SysuiTestCase() {

    private val viewOverlay = mock<ViewGroupOverlay>()
    private val overlayCaptor = argumentCaptor<Drawable>()
    private val darkDispatcher = mock<SysuiDarkIconDispatcher>()
    private val darkChange: MutableStateFlow<DarkChange> = MutableStateFlow(DarkChange.EMPTY)

    private val factory =
        StatusOverlayHoverListenerFactory(
            context.resources,
            FakeConfigurationController(),
            darkDispatcher
        )
    private val view = TestableStatusContainer(context, viewOverlay)

    private lateinit var looper: TestableLooper

    @Before
    fun setUp() {
        looper = TestableLooper.get(this)
        whenever(darkDispatcher.darkChangeFlow()).thenReturn(darkChange)
    }

    @Test
    fun onHoverStarted_addsOverlay() {
        view.setUpHoverListener()

        view.hoverStarted()

        assertThat(overlayDrawable).isNotNull()
    }

    @Test
    fun onHoverEnded_removesOverlay() {
        view.setUpHoverListener()

        view.hoverStarted() // stopped callback will be called only if hover has started
        view.hoverStopped()

        verify(viewOverlay).clear()
    }

    @Test
    fun onHoverStarted_overlayHasLightColor() {
        view.setUpHoverListener()

        view.hoverStarted()

        assertThat(overlayColor)
            .isEqualTo(context.resources.getColor(R.color.status_bar_icons_hover_color_light))
    }

    @Test
    fun onDarkAwareHoverStarted_withBlackIcons_overlayHasDarkColor() {
        view.setUpDarkAwareHoverListener()
        setIconsTint(Color.BLACK)

        view.hoverStarted()

        assertThat(overlayColor)
            .isEqualTo(context.resources.getColor(R.color.status_bar_icons_hover_color_dark))
    }

    @Test
    fun onHoverStarted_withBlackIcons_overlayHasLightColor() {
        view.setUpHoverListener()
        setIconsTint(Color.BLACK)

        view.hoverStarted()

        assertThat(overlayColor)
            .isEqualTo(context.resources.getColor(R.color.status_bar_icons_hover_color_light))
    }

    @Test
    fun onDarkAwareHoverStarted_withWhiteIcons_overlayHasLightColor() {
        view.setUpDarkAwareHoverListener()
        setIconsTint(Color.WHITE)

        view.hoverStarted()

        assertThat(overlayColor)
            .isEqualTo(context.resources.getColor(R.color.status_bar_icons_hover_color_light))
    }

    private fun View.setUpHoverListener() {
        setOnHoverListener(factory.createListener(view))
        attachView(view)
    }

    private fun View.setUpDarkAwareHoverListener() {
        setOnHoverListener(factory.createDarkAwareListener(view))
        attachView(view)
    }

    private fun attachView(view: View) {
        ViewUtils.attachView(view)
        // attaching is async so processAllMessages is required for view.repeatWhenAttached to run
        looper.processAllMessages()
    }

    private val overlayDrawable: Drawable
        get() {
            verify(viewOverlay).add(overlayCaptor.capture())
            return overlayCaptor.value
        }

    private val overlayColor
        get() = (overlayDrawable as PaintDrawable).paint.color

    private fun setIconsTint(@ColorInt color: Int) {
        // passing empty ArrayList is equivalent to just accepting passed color as icons color
        darkChange.value = DarkChange(/* areas= */ ArrayList(), /* darkIntensity= */ 1f, color)
    }

    private fun TestableStatusContainer.hoverStarted() {
        injectHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_ENTER))
    }

    private fun TestableStatusContainer.hoverStopped() {
        injectHoverEvent(hoverEvent(MotionEvent.ACTION_HOVER_EXIT))
    }

    class TestableStatusContainer(context: Context, private val mockOverlay: ViewGroupOverlay) :
        LinearLayout(context) {

        fun injectHoverEvent(event: MotionEvent) = dispatchHoverEvent(event)

        override fun getOverlay() = mockOverlay
    }

    private fun hoverEvent(action: Int): MotionEvent {
        return MotionEvent.obtain(
            /* downTime= */ SystemClock.uptimeMillis(),
            /* eventTime= */ SystemClock.uptimeMillis(),
            /* action= */ action,
            /* x= */ 0f,
            /* y= */ 0f,
            /* metaState= */ 0
        )
    }
}
