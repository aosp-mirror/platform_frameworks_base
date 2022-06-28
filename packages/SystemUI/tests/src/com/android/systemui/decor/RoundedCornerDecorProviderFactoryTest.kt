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

import android.testing.AndroidTestingRunner
import android.util.Size
import android.view.DisplayCutout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.spy

@RunWith(AndroidTestingRunner::class)
@SmallTest
class RoundedCornerDecorProviderFactoryTest : SysuiTestCase() {

    @Mock private lateinit var roundedCornerResDelegate: RoundedCornerResDelegate
    private lateinit var roundedCornerDecorProviderFactory: RoundedCornerDecorProviderFactory

    @Before
    fun setUp() {
        roundedCornerResDelegate = spy(RoundedCornerResDelegate(mContext.resources, null))
    }

    @Test
    fun testNoRoundedCorners() {
        Mockito.doReturn(false).`when`(roundedCornerResDelegate).hasTop
        Mockito.doReturn(false).`when`(roundedCornerResDelegate).hasBottom

        roundedCornerDecorProviderFactory =
                RoundedCornerDecorProviderFactory(roundedCornerResDelegate)

        Assert.assertEquals(false, roundedCornerDecorProviderFactory.hasProviders)
        Assert.assertEquals(0, roundedCornerDecorProviderFactory.providers.size)
    }

    @Test
    fun testOnlyHasTopRoundedCorners() {
        Mockito.doReturn(true).`when`(roundedCornerResDelegate).hasTop
        Mockito.doReturn(false).`when`(roundedCornerResDelegate).hasBottom
        Mockito.doReturn(Size(1, 1)).`when`(roundedCornerResDelegate).topRoundedSize

        roundedCornerDecorProviderFactory =
                RoundedCornerDecorProviderFactory(roundedCornerResDelegate)

        Assert.assertEquals(true, roundedCornerDecorProviderFactory.hasProviders)
        roundedCornerDecorProviderFactory.providers.let { providers ->
            Assert.assertEquals(2, providers.size)
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_top_left)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT))
            })
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_top_right)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_RIGHT))
            })
        }
    }

    @Test
    fun testHasRoundedCornersIfBottomWidthLargerThan0() {
        Mockito.doReturn(false).`when`(roundedCornerResDelegate).hasTop
        Mockito.doReturn(true).`when`(roundedCornerResDelegate).hasBottom
        Mockito.doReturn(Size(1, 1)).`when`(roundedCornerResDelegate).bottomRoundedSize

        roundedCornerDecorProviderFactory =
                RoundedCornerDecorProviderFactory(roundedCornerResDelegate)

        Assert.assertEquals(true, roundedCornerDecorProviderFactory.hasProviders)
        roundedCornerDecorProviderFactory.providers.let { providers ->
            Assert.assertEquals(2, providers.size)
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_bottom_left)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_BOTTOM)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT))
            })
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_bottom_right)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_BOTTOM)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_RIGHT))
            })
        }
    }

    @Test
    fun test4CornerDecorProvidersInfo() {
        Mockito.doReturn(true).`when`(roundedCornerResDelegate).hasTop
        Mockito.doReturn(true).`when`(roundedCornerResDelegate).hasBottom
        Mockito.doReturn(Size(10, 10)).`when`(roundedCornerResDelegate).topRoundedSize
        Mockito.doReturn(Size(10, 10)).`when`(roundedCornerResDelegate).bottomRoundedSize

        roundedCornerDecorProviderFactory =
                RoundedCornerDecorProviderFactory(roundedCornerResDelegate)

        Assert.assertEquals(true, roundedCornerDecorProviderFactory.hasProviders)
        roundedCornerDecorProviderFactory.providers.let { providers ->
            Assert.assertEquals(4, providers.size)
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_top_left)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT))
            })
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_top_right)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_TOP)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_RIGHT))
            })
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_bottom_left)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_BOTTOM)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_LEFT))
            })
            Assert.assertEquals(1, providers.count {
                ((it.viewId == R.id.rounded_corner_bottom_right)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_BOTTOM)
                        and it.alignedBounds.contains(DisplayCutout.BOUNDS_POSITION_RIGHT))
            })
        }
    }
}