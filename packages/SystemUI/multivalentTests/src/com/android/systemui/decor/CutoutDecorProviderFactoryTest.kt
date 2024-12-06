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

package com.android.systemui.decor

import android.graphics.Insets
import android.graphics.Rect
import android.testing.TestableResources
import android.util.RotationUtils
import android.util.Size
import android.view.Display
import android.view.DisplayCutout
import android.view.DisplayCutout.BOUNDS_POSITION_LENGTH
import android.view.DisplayInfo
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.MockitoAnnotations

@RunWith(AndroidJUnit4::class)
@SmallTest
class CutoutDecorProviderFactoryTest : SysuiTestCase() {

    @Mock private lateinit var display: Display
    private var testableRes: TestableResources? = null
    private lateinit var factory: CutoutDecorProviderFactory

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableRes = mContext.orCreateTestableResources
        factory = CutoutDecorProviderFactory(testableRes!!.resources, display)
    }

    private fun setupFillCutout(fillCutout: Boolean) {
        testableRes!!.addOverride(
            com.android.internal.R.bool.config_fillMainBuiltInDisplayCutout, fillCutout
        )
    }

    private fun setupDisplayInfo(
        displayCutout: DisplayCutout? = null,
        @Surface.Rotation rotation: Int = Surface.ROTATION_0,
        displayId: Int = -1
    ) {
        doAnswer {
            it.getArgument<DisplayInfo>(0).let { info ->
                info.displayCutout = displayCutout
                info.rotation = rotation
                info.displayId = displayId
            }
            true
        }.`when`(display).getDisplayInfo(any<DisplayInfo>())
    }

    private fun getCutout(
        safeInsets: Insets,
        cutoutBounds: Array<Rect?>,
        @Surface.Rotation rotation: Int = Surface.ROTATION_0,
        cutoutParentSizeForRotate: Size = Size(100, 200)
    ): DisplayCutout {
        val insets = RotationUtils.rotateInsets(safeInsets, rotation)
        val sorted = arrayOfNulls<Rect>(BOUNDS_POSITION_LENGTH)
        for (pos in 0 until BOUNDS_POSITION_LENGTH) {
            val rotatedPos = (pos - rotation + BOUNDS_POSITION_LENGTH) % BOUNDS_POSITION_LENGTH
            if (cutoutBounds[pos] != null) {
                RotationUtils.rotateBounds(
                    cutoutBounds[pos],
                    cutoutParentSizeForRotate.width,
                    cutoutParentSizeForRotate.height,
                    rotation
                )
            }
            sorted[rotatedPos] = cutoutBounds[pos]
        }
        return DisplayCutout(
            insets,
            sorted[DisplayCutout.BOUNDS_POSITION_LEFT],
            sorted[DisplayCutout.BOUNDS_POSITION_TOP],
            sorted[DisplayCutout.BOUNDS_POSITION_RIGHT],
            sorted[DisplayCutout.BOUNDS_POSITION_BOTTOM]
        )
    }

    @Test
    fun testGetNothingIfNoCutout() {
        setupFillCutout(false)

        Assert.assertFalse(factory.hasProviders)
        Assert.assertEquals(0, factory.providers.size)
    }

    @Test
    fun testGetTopCutoutProvider() {
        setupFillCutout(true)
        setupDisplayInfo(
            getCutout(
                safeInsets = Insets.of(0, 1, 0, 0),
                cutoutBounds = arrayOf(null, Rect(9, 0, 10, 1), null, null)
            )
        )

        Assert.assertTrue(factory.hasProviders)

        val providers = factory.providers
        Assert.assertEquals(1, providers.size)
        Assert.assertEquals(1, providers[0].numOfAlignedBound)
        Assert.assertEquals(DisplayCutout.BOUNDS_POSITION_TOP, providers[0].alignedBounds[0])
    }

    @Test
    fun testGetBottomCutoutProviderOnLandscape() {
        setupFillCutout(true)
        setupDisplayInfo(
            getCutout(
                safeInsets = Insets.of(0, 0, 0, 1),
                cutoutBounds = arrayOf(null, null, null, Rect(45, 199, 55, 200)),
                rotation = Surface.ROTATION_90
            ),
            Surface.ROTATION_90
        )

        Assert.assertTrue(factory.hasProviders)

        val providers = factory.providers
        Assert.assertEquals(1, providers.size)
        Assert.assertEquals(1, providers[0].numOfAlignedBound)
        Assert.assertEquals(DisplayCutout.BOUNDS_POSITION_BOTTOM, providers[0].alignedBounds[0])
    }

    @Test
    fun testGetLeftCutoutProviderOnSeascape() {
        setupFillCutout(true)
        setupDisplayInfo(
            getCutout(
                safeInsets = Insets.of(1, 0, 0, 0),
                cutoutBounds = arrayOf(Rect(0, 20, 1, 40), null, null, null),
                rotation = Surface.ROTATION_270
            ),
            Surface.ROTATION_270
        )

        Assert.assertTrue(factory.hasProviders)

        val providers = factory.providers
        Assert.assertEquals(1, providers.size)
        Assert.assertEquals(1, providers[0].numOfAlignedBound)
        Assert.assertEquals(DisplayCutout.BOUNDS_POSITION_LEFT, providers[0].alignedBounds[0])
    }

    @Test
    fun testGetTopRightCutoutProviderOnReverse() {
        setupFillCutout(true)
        setupDisplayInfo(
            getCutout(
                safeInsets = Insets.of(0, 1, 1, 0),
                cutoutBounds = arrayOf(
                    null,
                    Rect(9, 0, 10, 1),
                    Rect(99, 40, 100, 60),
                    null
                ),
                rotation = Surface.ROTATION_180
            ),
            Surface.ROTATION_180
        )

        Assert.assertTrue(factory.hasProviders)

        val providers = factory.providers
        Assert.assertEquals(2, providers.size)
        Assert.assertEquals(1, providers[0].numOfAlignedBound)
        Assert.assertEquals(1, providers[1].numOfAlignedBound)
        providers.sortedBy { it.alignedBounds[0] }.let {
            Assert.assertEquals(DisplayCutout.BOUNDS_POSITION_TOP, it[0].alignedBounds[0])
            Assert.assertEquals(DisplayCutout.BOUNDS_POSITION_RIGHT, it[1].alignedBounds[0])
        }
    }
}
