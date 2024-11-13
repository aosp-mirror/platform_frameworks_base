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

package com.android.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.never
import com.android.internal.R
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidJUnit4::class)
@SmallTest
class DisplayCutoutBaseViewTest : SysuiTestCase() {

    @Mock private lateinit var mockCanvas: Canvas
    @Mock private lateinit var mockRootView: View
    @Mock private lateinit var mockDisplay: Display
    @Mock private lateinit var mockContext: Context

    private lateinit var cutoutBaseView: DisplayCutoutBaseView
    private val cutout: DisplayCutout = DisplayCutout.Builder()
            .setSafeInsets(Insets.of(0, 2, 0, 0))
            .setBoundingRectTop(Rect(1, 0, 2, 2))
            .build()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testBoundingRectsToRegion() {
        setupDisplayCutoutBaseView(true /* fillCutout */, true /* hasCutout */)
        val rect = Rect(1, 2, 3, 4)
        assertThat(cutoutBaseView.rectsToRegion(listOf(rect)).bounds).isEqualTo(rect)
    }

    @Test
    fun testDrawCutout_fillCutout() {
        setupDisplayCutoutBaseView(true /* fillCutout */, true /* hasCutout */)
        cutoutBaseView.onDraw(mockCanvas)

        verify(cutoutBaseView).drawCutouts(mockCanvas)
    }

    @Test
    fun testDrawCutout_notFillCutout() {
        setupDisplayCutoutBaseView(false /* fillCutout */, true /* hasCutout */)
        cutoutBaseView.onDraw(mockCanvas)

        verify(cutoutBaseView, never()).drawCutouts(mockCanvas)
    }

    @Test
    fun testShouldInterceptTouch_hasCutout() {
        setupDisplayCutoutBaseView(true /* fillCutout */, true /* hasCutout */)
        cutoutBaseView.updateCutout()

        assertThat(cutoutBaseView.shouldInterceptTouch()).isTrue()
    }

    @Test
    fun testShouldInterceptTouch_noCutout() {
        setupDisplayCutoutBaseView(true /* fillCutout */, false /* hasCutout */)
        cutoutBaseView.updateCutout()

        assertThat(cutoutBaseView.shouldInterceptTouch()).isFalse()
    }

    @Test
    fun testGetInterceptRegion_hasCutout() {
        setupDisplayCutoutBaseView(true /* fillCutout */, true /* hasCutout */)
        whenever(mockRootView.left).thenReturn(0)
        whenever(mockRootView.top).thenReturn(0)
        whenever(mockRootView.right).thenReturn(100)
        whenever(mockRootView.bottom).thenReturn(200)

        val expect = Region()
        expect.op(cutout.boundingRectTop, Region.Op.UNION)
        expect.op(0, 0, 100, 200, Region.Op.INTERSECT)

        cutoutBaseView.updateCutout()

        assertThat(cutoutBaseView.interceptRegion).isEqualTo(expect)
    }

    @Test
    fun testGetInterceptRegion_noCutout() {
        setupDisplayCutoutBaseView(true /* fillCutout */, false /* hasCutout */)
        cutoutBaseView.updateCutout()

        assertThat(cutoutBaseView.interceptRegion).isNull()
    }

    @Test
    fun testCutoutProtection() {
        setupDisplayCutoutBaseView(true /* fillCutout */, false /* hasCutout */)
        val bounds = Rect(0, 0, 10, 10)
        val path = Path()
        val pathBounds = RectF(bounds)
        path.addRect(pathBounds, Path.Direction.CCW)

        context.mainExecutor.execute {
            cutoutBaseView.setProtection(path, bounds)
            cutoutBaseView.enableShowProtection(true)
        }
        waitForIdleSync()

        assertThat(cutoutBaseView.protectionPath.isRect(pathBounds)).isTrue()
        assertThat(cutoutBaseView.protectionRect).isEqualTo(pathBounds)
    }

    @Test
    fun testCutoutProtection_withDisplayRatio() {
        setupDisplayCutoutBaseView(true /* fillCutout */, false /* hasCutout */)
        whenever(cutoutBaseView.getPhysicalPixelDisplaySizeRatio()).thenReturn(0.5f)
        val bounds = Rect(0, 0, 10, 10)
        val path = Path()
        val pathBounds = RectF(bounds)
        path.addRect(pathBounds, Path.Direction.CCW)

        context.mainExecutor.execute {
            cutoutBaseView.setProtection(path, bounds)
            cutoutBaseView.enableShowProtection(true)
        }
        waitForIdleSync()

        assertThat(cutoutBaseView.protectionPath.isRect(pathBounds)).isTrue()
        assertThat(cutoutBaseView.protectionRect).isEqualTo(RectF(0f, 0f, 5f, 5f))
    }

    private fun setupDisplayCutoutBaseView(fillCutout: Boolean, hasCutout: Boolean) {
        mContext.orCreateTestableResources.addOverride(
                R.array.config_displayUniqueIdArray, arrayOf<String>())
        mContext.orCreateTestableResources.addOverride(
                R.bool.config_fillMainBuiltInDisplayCutout, fillCutout)

        cutoutBaseView = spy(DisplayCutoutBaseView(mContext))

        whenever(cutoutBaseView.context).thenReturn(mockContext)
        whenever(mockContext.display).thenReturn(mockDisplay)
        whenever(mockDisplay.uniqueId).thenReturn("mockDisplayUniqueId")
        whenever(cutoutBaseView.rootView).thenReturn(mockRootView)
        whenever(cutoutBaseView.getPhysicalPixelDisplaySizeRatio()).thenReturn(1f)
        whenever(mockDisplay.getDisplayInfo(eq(cutoutBaseView.displayInfo))
        ).then {
            val info = it.getArgument<DisplayInfo>(0)
            info.displayCutout = if (hasCutout) cutout else null
            return@then true
        }
    }
}
