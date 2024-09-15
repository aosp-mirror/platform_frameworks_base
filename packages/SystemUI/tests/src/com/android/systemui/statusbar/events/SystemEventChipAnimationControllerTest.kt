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

package com.android.systemui.statusbar.events

import android.content.Context
import android.graphics.Insets
import android.graphics.Rect
import android.testing.TestableLooper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.statusbar.phone.StatusBarContentInsetsChangedListener
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class SystemEventChipAnimationControllerTest : SysuiTestCase() {
    private lateinit var controller: SystemEventChipAnimationController

    @get:Rule val animatorTestRule = AnimatorTestRule(this)
    @Mock private lateinit var sbWindowController: StatusBarWindowController
    @Mock private lateinit var insetsProvider: StatusBarContentInsetsProvider

    private var testView = TestView(mContext)
    private var viewCreator: ViewCreator = { testView }

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        // StatusBarWindowController is mocked. The addViewToWindow function needs to be mocked to
        // ensure that the chip view is added to a parent view
        whenever(sbWindowController.addViewToWindow(any(), any())).then {
            val statusbarFake = FrameLayout(mContext)
            statusbarFake.layout(
                portraitArea.left,
                portraitArea.top,
                portraitArea.right,
                portraitArea.bottom,
            )
            statusbarFake.addView(
                it.arguments[0] as View,
                it.arguments[1] as FrameLayout.LayoutParams
            )
        }

        whenever(insetsProvider.getStatusBarContentInsetsForCurrentRotation())
            .thenReturn(
                Insets.of(
                    /* left= */ insets,
                    /* top= */ insets,
                    /* right= */ insets,
                    /* bottom= */ 0
                )
            )
        whenever(insetsProvider.getStatusBarContentAreaForCurrentRotation())
            .thenReturn(portraitArea)

        controller =
            SystemEventChipAnimationController(
                context = mContext,
                statusBarWindowController = sbWindowController,
                contentInsetsProvider = insetsProvider
            )
    }

    @Test
    fun prepareChipAnimation_lazyInitializes() {
        // Until Dagger can do our initialization, make sure that the first chip animation calls
        // init()
        assertFalse(controller.initialized)
        controller.prepareChipAnimation(viewCreator)
        assertTrue(controller.initialized)
    }

    @Test
    fun prepareChipAnimation_positionsChip() {
        controller.prepareChipAnimation(viewCreator)
        val chipRect = controller.chipBounds

        // SB area = 10, 10, 990, 100
        // chip size = 0, 0, 100, 50
        assertThat(chipRect).isEqualTo(Rect(890, 30, 990, 80))
    }

    @Test
    fun prepareChipAnimation_rotation_repositionsChip() {
        controller.prepareChipAnimation(viewCreator)

        // Chip has been prepared, and is located at (890, 30, 990, 75)
        // Rotation should put it into its landscape location:
        // SB area = 10, 10, 1990, 80
        // chip size = 0, 0, 100, 50

        whenever(insetsProvider.getStatusBarContentAreaForCurrentRotation())
            .thenReturn(landscapeArea)
        getInsetsListener().onStatusBarContentInsetsChanged()

        val chipRect = controller.chipBounds
        assertThat(chipRect).isEqualTo(Rect(1890, 20, 1990, 70))
    }

    /** regression test for (b/289378932) */
    @Test
    fun fullScreenStatusBar_positionsChipAtTop_withTopGravity() {
        // In the case of a fullscreen status bar window, the content insets area is still correct
        // (because it uses the dimens), but the window can be full screen. This seems to happen
        // when launching an app from the ongoing call chip.

        // GIVEN layout the status bar window fullscreen portrait
        whenever(sbWindowController.addViewToWindow(any(), any())).then {
            val statusbarFake = FrameLayout(mContext)
            statusbarFake.layout(
                fullScreenSb.left,
                fullScreenSb.top,
                fullScreenSb.right,
                fullScreenSb.bottom,
            )

            val lp = it.arguments[1] as FrameLayout.LayoutParams
            assertThat(lp.gravity and Gravity.VERTICAL_GRAVITY_MASK).isEqualTo(Gravity.TOP)

            statusbarFake.addView(
                it.arguments[0] as View,
                lp,
            )
        }

        // GIVEN insets provider gives the correct content area
        whenever(insetsProvider.getStatusBarContentAreaForCurrentRotation())
            .thenReturn(portraitArea)

        // WHEN the controller lays out the chip in a fullscreen window
        controller.prepareChipAnimation(viewCreator)

        // THEN it still aligns the chip to the content area provided by the insets provider
        val chipRect = controller.chipBounds
        assertThat(chipRect).isEqualTo(Rect(890, 30, 990, 80))
    }

    private class TestView(context: Context) : View(context), BackgroundAnimatableView {
        override val view: View
            get() = this

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(100, 50)
        }

        override fun setBoundsForAnimation(l: Int, t: Int, r: Int, b: Int) {
            setLeftTopRightBottom(l, t, r, b)
        }
    }

    private fun getInsetsListener(): StatusBarContentInsetsChangedListener {
        val callbackCaptor = argumentCaptor<StatusBarContentInsetsChangedListener>()
        verify(insetsProvider).addCallback(capture(callbackCaptor))
        return callbackCaptor.value!!
    }

    companion object {
        private val portraitArea = Rect(10, 10, 990, 100)
        private val landscapeArea = Rect(10, 10, 1990, 80)
        private val fullScreenSb = Rect(10, 10, 990, 2000)

        // 10px insets on both sides
        private const val insets = 10
    }
}
