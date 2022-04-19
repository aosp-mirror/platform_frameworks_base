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
import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.util.Size
import androidx.annotation.DrawableRes
import androidx.test.filters.SmallTest
import com.android.internal.R as InternalR
import com.android.systemui.R as SystemUIR
import com.android.systemui.tests.R
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
    fun testTopAndBottomRoundedCornerExist() {
        setupResources(radius = 5)
        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)
        assertEquals(true, roundedCornerResDelegate.hasTop)
        assertEquals(true, roundedCornerResDelegate.hasBottom)
    }

    @Test
    fun testTopRoundedCornerExist() {
        setupResources(radiusTop = 10)
        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)
        assertEquals(true, roundedCornerResDelegate.hasTop)
        assertEquals(false, roundedCornerResDelegate.hasBottom)
    }

    @Test
    fun testBottomRoundedCornerExist() {
        setupResources(radiusBottom = 15)
        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)
        assertEquals(false, roundedCornerResDelegate.hasTop)
        assertEquals(true, roundedCornerResDelegate.hasBottom)
    }

    @Test
    fun testUpdateDisplayUniqueId() {
        setupResources(radius = 100,
                roundedTopDrawable = getTestsDrawable(R.drawable.rounded3px),
                roundedBottomDrawable = getTestsDrawable(R.drawable.rounded4px))

        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)

        assertEquals(Size(3, 3), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(4, 4), roundedCornerResDelegate.bottomRoundedSize)

        setupResources(radius = 100,
                roundedTopDrawable = getTestsDrawable(R.drawable.rounded4px),
                roundedBottomDrawable = getTestsDrawable(R.drawable.rounded5px))

        roundedCornerResDelegate.updateDisplayUniqueId("test", null)

        assertEquals(Size(4, 4), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(5, 5), roundedCornerResDelegate.bottomRoundedSize)
    }

    @Test
    fun testNotUpdateDisplayUniqueIdButChangeRefreshToken() {
        setupResources(radius = 100,
                roundedTopDrawable = getTestsDrawable(R.drawable.rounded3px),
                roundedBottomDrawable = getTestsDrawable(R.drawable.rounded4px))

        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)

        assertEquals(Size(3, 3), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(4, 4), roundedCornerResDelegate.bottomRoundedSize)

        setupResources(radius = 100,
                roundedTopDrawable = getTestsDrawable(R.drawable.rounded4px),
                roundedBottomDrawable = getTestsDrawable(R.drawable.rounded5px))

        roundedCornerResDelegate.updateDisplayUniqueId(null, 1)

        assertEquals(Size(4, 4), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(5, 5), roundedCornerResDelegate.bottomRoundedSize)
    }

    @Test
    fun testUpdateTuningSizeFactor() {
        setupResources(radius = 100,
                roundedTopDrawable = getTestsDrawable(R.drawable.rounded3px),
                roundedBottomDrawable = getTestsDrawable(R.drawable.rounded4px))

        roundedCornerResDelegate = RoundedCornerResDelegate(mContext.resources, null)

        val factor = 5
        roundedCornerResDelegate.updateTuningSizeFactor(factor, 1)
        val length = (factor * mContext.resources.displayMetrics.density).toInt()

        assertEquals(Size(length, length), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(length, length), roundedCornerResDelegate.bottomRoundedSize)

        roundedCornerResDelegate.updateTuningSizeFactor(null, 2)

        assertEquals(Size(3, 3), roundedCornerResDelegate.topRoundedSize)
        assertEquals(Size(4, 4), roundedCornerResDelegate.bottomRoundedSize)
    }

    private fun getTestsDrawable(@DrawableRes drawableId: Int): Drawable? {
        return mContext.createPackageContext("com.android.systemui.tests", 0)
                .getDrawable(drawableId)
    }

    private fun setupResources(
        radius: Int? = null,
        radiusTop: Int? = null,
        radiusBottom: Int? = null,
        roundedTopDrawable: Drawable? = null,
        roundedBottomDrawable: Drawable? = null
    ) {
        mContext.orCreateTestableResources.let { res ->
            res.addOverride(InternalR.array.config_displayUniqueIdArray, arrayOf<String>())
            res.addOverride(InternalR.array.config_roundedCornerRadiusArray, mockTypedArray)
            res.addOverride(InternalR.array.config_roundedCornerTopRadiusArray, mockTypedArray)
            res.addOverride(InternalR.array.config_roundedCornerBottomRadiusArray, mockTypedArray)
            res.addOverride(SystemUIR.array.config_roundedCornerDrawableArray, mockTypedArray)
            res.addOverride(SystemUIR.array.config_roundedCornerTopDrawableArray, mockTypedArray)
            res.addOverride(SystemUIR.array.config_roundedCornerBottomDrawableArray, mockTypedArray)
            res.addOverride(com.android.internal.R.dimen.rounded_corner_radius, radius ?: 0)
            res.addOverride(com.android.internal.R.dimen.rounded_corner_radius_top, radiusTop ?: 0)
            res.addOverride(com.android.internal.R.dimen.rounded_corner_radius_bottom,
                    radiusBottom ?: 0)
            roundedTopDrawable?.let { drawable ->
                res.addOverride(SystemUIR.drawable.rounded_corner_top, drawable)
            }
            roundedBottomDrawable?.let { drawable ->
                res.addOverride(SystemUIR.drawable.rounded_corner_bottom, drawable)
            }
        }
    }
}
