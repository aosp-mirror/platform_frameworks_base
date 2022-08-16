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

import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.graphics.common.DisplayDecorationSupport
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayInfo
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ScreenDecorHwcLayerTest : SysuiTestCase() {

    @Mock private lateinit var mockDisplay: Display
    @Mock private lateinit var mockRootView: View

    private val displayWidth = 100
    private val displayHeight = 200
    private val cutoutSize = 10
    private val roundedSizeTop = 15
    private val roundedSizeBottom = 20

    private lateinit var decorHwcLayer: ScreenDecorHwcLayer
    private val cutoutTop: DisplayCutout = DisplayCutout.Builder()
        .setSafeInsets(Insets.of(0, cutoutSize, 0, 0))
        .setBoundingRectTop(Rect(1, 0, 2, cutoutSize))
        .build()

    private val cutoutRight: DisplayCutout = DisplayCutout.Builder()
        .setSafeInsets(Insets.of(0, 0, cutoutSize, 0))
        .setBoundingRectRight(Rect(displayWidth - cutoutSize, 50, displayWidth, 52))
        .build()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mContext.orCreateTestableResources.addOverride(
            R.array.config_displayUniqueIdArray, arrayOf<String>())
        mContext.orCreateTestableResources.addOverride(
            R.bool.config_fillMainBuiltInDisplayCutout, true)

        val decorationSupport = DisplayDecorationSupport()
        decorationSupport.format = PixelFormat.R_8
        decorHwcLayer = Mockito.spy(ScreenDecorHwcLayer(mContext, decorationSupport))
        whenever(decorHwcLayer.width).thenReturn(displayWidth)
        whenever(decorHwcLayer.height).thenReturn(displayHeight)
        whenever(decorHwcLayer.display).thenReturn(mockDisplay)
        whenever(decorHwcLayer.rootView).thenReturn(mockRootView)
        whenever(mockRootView.left).thenReturn(0)
        whenever(mockRootView.top).thenReturn(0)
        whenever(mockRootView.right).thenReturn(displayWidth)
        whenever(mockRootView.bottom).thenReturn(displayHeight)
    }

    @Test
    fun testTransparentRegion_noCutout_noRoundedCorner_noProtection() {
        setupConfigs(null, false, false, 0, 0, RectF(), 0f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
                .isEqualTo(Rect(0, 0, decorHwcLayer.width, decorHwcLayer.height))
    }

    @Test
    fun testTransparentRegion_onlyShortEdgeCutout() {
        setupConfigs(cutoutTop, false, false, 0, 0, RectF(), 0f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
                .isEqualTo(Rect(0, cutoutSize, decorHwcLayer.width, decorHwcLayer.height))
    }

    @Test
    fun testTransparentRegion_onlyLongEdgeCutout() {
        setupConfigs(cutoutRight, false, false, 0, 0, RectF(), 0f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
                .isEqualTo(Rect(0, 0, decorHwcLayer.width - cutoutSize, decorHwcLayer.height))
    }

    @Test
    fun testTransparentRegion_onlyRoundedCorners() {
        setupConfigs(null, true, true, roundedSizeTop, roundedSizeBottom, RectF(), 0f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
            .isEqualTo(Rect(0, roundedSizeTop, decorHwcLayer.width,
                decorHwcLayer.height - roundedSizeBottom))
    }

    @Test
    fun testTransparentRegion_onlyCutoutProtection() {
        setupConfigs(null, false, false, 0, 0, RectF(48f, 1f, 52f, 5f), 0.5f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
            .isEqualTo(Rect(0, 4, decorHwcLayer.width, decorHwcLayer.height))

        decorHwcLayer.cameraProtectionProgress = 1f

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
            .isEqualTo(Rect(0, 5, decorHwcLayer.width, decorHwcLayer.height))
    }

    @Test
    fun testTransparentRegion_hasShortEdgeCutout_hasRoundedCorner_hasCutoutProtection() {
        setupConfigs(cutoutTop, true, true, roundedSizeTop, roundedSizeBottom,
                RectF(48f, 1f, 52f, 5f), 1f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
            .isEqualTo(Rect(0, 15, decorHwcLayer.width, decorHwcLayer.height - 20))
    }

    @Test
    fun testTransparentRegion_hasLongEdgeCutout_hasRoundedCorner_hasCutoutProtection() {
        setupConfigs(cutoutRight, true, true, roundedSizeTop, roundedSizeBottom,
                RectF(48f, 1f, 52f, 5f), 1f)

        decorHwcLayer.calculateTransparentRect()

        assertThat(decorHwcLayer.transparentRect)
            .isEqualTo(Rect(20, 5, decorHwcLayer.width - 20, decorHwcLayer.height))
    }

    private fun setupConfigs(
        cutout: DisplayCutout?,
        hasRoundedTop: Boolean,
        hasRoundedBottom: Boolean,
        roundedTop: Int,
        roundedBottom: Int,
        protectionRect: RectF,
        protectionProgress: Float
    ) {
        whenever(mockDisplay.getDisplayInfo(eq(decorHwcLayer.displayInfo))
        ).then {
            val info = it.getArgument<DisplayInfo>(0)
            info.displayCutout = cutout
            return@then true
        }
        decorHwcLayer.updateRoundedCornerExistenceAndSize(hasRoundedTop, hasRoundedBottom,
                roundedTop, roundedBottom)
        decorHwcLayer.protectionRect.set(protectionRect)
        decorHwcLayer.cameraProtectionProgress = protectionProgress
        decorHwcLayer.updateCutout()
    }
}
