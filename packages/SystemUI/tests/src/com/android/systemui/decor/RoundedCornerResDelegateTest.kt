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

import android.content.res.TypedArray
import android.graphics.drawable.VectorDrawable
import android.testing.AndroidTestingRunner
import android.testing.TestableResources
import android.util.Size
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class RoundedCornerResDelegateTest : SysuiTestCase() {

    private lateinit var roundedCornerResDelegate: RoundedCornerResDelegate
    @Mock private lateinit var mockTypedArray: TypedArray

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testReloadAllAndDefaultRadius() {
        mContext.orCreateTestableResources.addOverrides(
                mockTypeArray = mockTypedArray,
                radius = 3,
                radiusTop = 0,
                radiusBottom = 4,
                multipleRadius = false)

        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)

        assertEquals(Size(3, 3), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(4, 4), roundedCornerResDelegate.bottomRoundedSize)
        assertEquals(false, roundedCornerResDelegate.isMultipleRadius)

        mContext.orCreateTestableResources.addOverrides(
                mockTypeArray = mockTypedArray,
                radius = 5,
                radiusTop = 6,
                radiusBottom = 0)

        roundedCornerResDelegate.reloadAll("test")

        assertEquals(Size(6, 6), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(5, 5), roundedCornerResDelegate.bottomRoundedSize)
    }

    @Test
    fun testUpdateTuningSizeFactor() {
        mContext.orCreateTestableResources.addOverrides(
                mockTypeArray = mockTypedArray,
                radiusTop = 0,
                radiusBottom = 0,
                multipleRadius = false)

        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)

        val factor = 5
        roundedCornerResDelegate.updateTuningSizeFactor(factor)
        val length = (factor * mContext.resources.displayMetrics.density).toInt()

        assertEquals(Size(length, length), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(length, length), roundedCornerResDelegate.bottomRoundedSize)
    }

    @Test
    fun testReadDefaultRadiusWhen0() {
        mContext.orCreateTestableResources.addOverrides(
                mockTypeArray = mockTypedArray,
                radius = 3,
                radiusTop = 0,
                radiusBottom = 0,
                multipleRadius = false)

        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)

        assertEquals(Size(3, 3), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(3, 3), roundedCornerResDelegate.bottomRoundedSize)
    }

    @Test
    fun testReadMultipleRadius() {
        val d = mContext.getDrawable(R.drawable.rounded) as VectorDrawable
        val multipleRadiusSize = Size(d.intrinsicWidth, d.intrinsicHeight)
        mContext.orCreateTestableResources.addOverrides(
                mockTypeArray = mockTypedArray,
                multipleRadius = true)
        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)
        assertEquals(multipleRadiusSize, roundedCornerResDelegate.topRoundedSize)
        assertEquals(multipleRadiusSize, roundedCornerResDelegate.bottomRoundedSize)
    }
}

private fun TestableResources.addOverrides(
    mockTypeArray: TypedArray,
    radius: Int? = null,
    radiusTop: Int? = null,
    radiusBottom: Int? = null,
    multipleRadius: Boolean? = null
) {
    addOverride(com.android.internal.R.array.config_displayUniqueIdArray, arrayOf<String>())
    addOverride(com.android.internal.R.array.config_roundedCornerRadiusArray, mockTypeArray)
    addOverride(com.android.internal.R.array.config_roundedCornerTopRadiusArray, mockTypeArray)
    addOverride(com.android.internal.R.array.config_roundedCornerBottomRadiusArray, mockTypeArray)
    addOverride(R.array.config_roundedCornerDrawableArray, mockTypeArray)
    addOverride(R.array.config_roundedCornerTopDrawableArray, mockTypeArray)
    addOverride(R.array.config_roundedCornerBottomDrawableArray, mockTypeArray)
    addOverride(R.array.config_roundedCornerMultipleRadiusArray, mockTypeArray)
    radius?.let { addOverride(com.android.internal.R.dimen.rounded_corner_radius, it) }
    radiusTop?.let { addOverride(com.android.internal.R.dimen.rounded_corner_radius_top, it) }
    radiusBottom?.let { addOverride(com.android.internal.R.dimen.rounded_corner_radius_bottom, it) }
    multipleRadius?.let { addOverride(R.bool.config_roundedCornerMultipleRadius, it) }
}