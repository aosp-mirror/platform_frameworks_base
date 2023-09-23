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

import android.graphics.Color
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.DisplayCutout
import android.view.Surface
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class OverlayWindowTest : SysuiTestCase() {

    companion object {
        private val TEST_DECOR_VIEW_ID_1 = R.id.privacy_dot_top_left_container
        private val TEST_DECOR_VIEW_ID_2 = R.id.privacy_dot_bottom_left_container
        private val TEST_DECOR_VIEW_ID_3 = R.id.privacy_dot_bottom_right_container
    }

    private lateinit var overlay: OverlayWindow
    private lateinit var decorProvider1: DecorProvider
    private lateinit var decorProvider2: DecorProvider
    private lateinit var decorProvider3: DecorProvider

    @Before
    fun setUp() {
        decorProvider1 = spy(
            PrivacyDotCornerDecorProviderImpl(
                viewId = TEST_DECOR_VIEW_ID_1,
                alignedBound1 = DisplayCutout.BOUNDS_POSITION_TOP,
                alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                layoutId = R.layout.privacy_dot_top_left
            )
        )
        decorProvider2 = spy(
            PrivacyDotCornerDecorProviderImpl(
                viewId = TEST_DECOR_VIEW_ID_2,
                alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                alignedBound2 = DisplayCutout.BOUNDS_POSITION_LEFT,
                layoutId = R.layout.privacy_dot_bottom_left
            )
        )
        decorProvider3 = spy(
            PrivacyDotCornerDecorProviderImpl(
                viewId = TEST_DECOR_VIEW_ID_3,
                alignedBound1 = DisplayCutout.BOUNDS_POSITION_BOTTOM,
                alignedBound2 = DisplayCutout.BOUNDS_POSITION_RIGHT,
                layoutId = R.layout.privacy_dot_bottom_right
            )
        )
        overlay = OverlayWindow(mContext)
    }

    @Test
    fun testAddProvider() {
        @Surface.Rotation val rotation = Surface.ROTATION_270
        overlay.addDecorProvider(decorProvider1, rotation, Color.BLACK)
        overlay.addDecorProvider(decorProvider2, rotation, Color.YELLOW)

        verify(decorProvider1, times(1)).inflateView(
                mContext, overlay.rootView, rotation, Color.BLACK
        )
        verify(decorProvider2, times(1)).inflateView(
                mContext, overlay.rootView, rotation, Color.YELLOW
        )

        val view1FoundFromRootView = overlay.rootView.findViewById<View>(TEST_DECOR_VIEW_ID_1)
        Assert.assertNotNull(view1FoundFromRootView)
        Assert.assertEquals(view1FoundFromRootView, overlay.getView(TEST_DECOR_VIEW_ID_1))
        val view2FoundFromRootView = overlay.rootView.findViewById<View>(TEST_DECOR_VIEW_ID_2)
        Assert.assertNotNull(view2FoundFromRootView)
        Assert.assertEquals(view2FoundFromRootView, overlay.getView(TEST_DECOR_VIEW_ID_2))
    }

    @Test
    fun testRemoveView() {
        overlay.addDecorProvider(decorProvider1, Surface.ROTATION_270, Color.BLACK)
        overlay.addDecorProvider(decorProvider2, Surface.ROTATION_270, Color.BLACK)
        overlay.removeView(TEST_DECOR_VIEW_ID_1)

        val viewFoundFromRootView = overlay.rootView.findViewById<View>(TEST_DECOR_VIEW_ID_1)
        Assert.assertNull(viewFoundFromRootView)
        Assert.assertNull(overlay.getView(TEST_DECOR_VIEW_ID_1))
    }

    @Test
    fun testOnReloadResAndMeasureWithoutIds() {
        overlay.addDecorProvider(decorProvider1, Surface.ROTATION_0, Color.BLACK)
        overlay.addDecorProvider(decorProvider2, Surface.ROTATION_0, Color.BLACK)

        overlay.onReloadResAndMeasure(
            reloadToken = 1,
            rotation = Surface.ROTATION_90,
            tintColor = Color.BLACK,
            displayUniqueId = null
        )
        verify(decorProvider1, times(1)).onReloadResAndMeasure(
                overlay.getView(TEST_DECOR_VIEW_ID_1)!!, 1, Surface.ROTATION_90, Color.BLACK, null
        )
        verify(decorProvider2, times(1)).onReloadResAndMeasure(
                overlay.getView(TEST_DECOR_VIEW_ID_2)!!, 1, Surface.ROTATION_90, Color.BLACK, null
        )
    }

    @Test
    fun testOnReloadResAndMeasureWithIds() {
        overlay.addDecorProvider(decorProvider1, Surface.ROTATION_0, Color.BLACK)
        overlay.addDecorProvider(decorProvider2, Surface.ROTATION_0, Color.BLACK)

        overlay.onReloadResAndMeasure(
            filterIds = arrayOf(TEST_DECOR_VIEW_ID_2),
            reloadToken = 1,
            rotation = Surface.ROTATION_90,
            tintColor = Color.BLACK,
            displayUniqueId = null
        )
        verify(decorProvider1, never()).onReloadResAndMeasure(
                overlay.getView(TEST_DECOR_VIEW_ID_1)!!, 1, Surface.ROTATION_90, Color.BLACK, null
        )
        verify(decorProvider2, times(1)).onReloadResAndMeasure(
                overlay.getView(TEST_DECOR_VIEW_ID_2)!!, 1, Surface.ROTATION_90, Color.BLACK, null
        )
    }

    @Test
    fun testRemoveRedundantViewsWithNullParameter() {
        overlay.addDecorProvider(decorProvider1, Surface.ROTATION_270, Color.BLACK)
        overlay.addDecorProvider(decorProvider2, Surface.ROTATION_270, Color.BLACK)

        overlay.removeRedundantViews(null)

        Assert.assertNull(overlay.getView(TEST_DECOR_VIEW_ID_1))
        Assert.assertNull(overlay.rootView.findViewById(TEST_DECOR_VIEW_ID_1))
        Assert.assertNull(overlay.getView(TEST_DECOR_VIEW_ID_2))
        Assert.assertNull(overlay.rootView.findViewById(TEST_DECOR_VIEW_ID_2))
    }

    @Test
    fun testRemoveRedundantViewsWith2Providers() {
        overlay.addDecorProvider(decorProvider1, Surface.ROTATION_270, Color.BLACK)
        overlay.addDecorProvider(decorProvider2, Surface.ROTATION_270, Color.BLACK)

        overlay.removeRedundantViews(
            IntArray(2).apply {
                this[0] = TEST_DECOR_VIEW_ID_3
                this[1] = TEST_DECOR_VIEW_ID_1
            }
        )

        Assert.assertNotNull(overlay.getView(TEST_DECOR_VIEW_ID_1))
        Assert.assertNotNull(overlay.rootView.findViewById(TEST_DECOR_VIEW_ID_1))
        Assert.assertNull(overlay.getView(TEST_DECOR_VIEW_ID_2))
        Assert.assertNull(overlay.rootView.findViewById(TEST_DECOR_VIEW_ID_2))
    }

    @Test
    fun testHasSameProviders() {
        Assert.assertTrue(overlay.hasSameProviders(emptyList()))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider1)))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider2)))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider2, decorProvider1)))

        overlay.addDecorProvider(decorProvider1, Surface.ROTATION_0, Color.BLACK)
        Assert.assertFalse(overlay.hasSameProviders(emptyList()))
        Assert.assertTrue(overlay.hasSameProviders(listOf(decorProvider1)))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider2)))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider2, decorProvider1)))

        overlay.addDecorProvider(decorProvider2, Surface.ROTATION_0, Color.BLACK)
        Assert.assertFalse(overlay.hasSameProviders(emptyList()))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider1)))
        Assert.assertFalse(overlay.hasSameProviders(listOf(decorProvider2)))
        Assert.assertTrue(overlay.hasSameProviders(listOf(decorProvider2, decorProvider1)))
    }
}
