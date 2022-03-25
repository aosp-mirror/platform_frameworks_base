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
import android.testing.TestableLooper.RunWithLooper
import android.view.DisplayCutout
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class OverlayWindowTest : SysuiTestCase() {

    companion object {
        private val TEST_DECOR_VIEW_ID = R.id.privacy_dot_bottom_right_container
        private val TEST_DECOR_LAYOUT_ID = R.layout.privacy_dot_bottom_right
    }

    private lateinit var overlay: OverlayWindow

    @Mock private lateinit var layoutInflater: LayoutInflater
    @Mock private lateinit var decorProvider: DecorProvider

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        layoutInflater = spy(LayoutInflater.from(mContext))

        overlay = OverlayWindow(layoutInflater, DisplayCutout.BOUNDS_POSITION_RIGHT)

        whenever(decorProvider.viewId).thenReturn(TEST_DECOR_VIEW_ID)
        whenever(decorProvider.inflateView(
            eq(layoutInflater),
            eq(overlay.rootView),
            anyInt())
        ).then {
            val layoutInflater = it.getArgument<LayoutInflater>(0)
            val parent = it.getArgument<ViewGroup>(1)
            layoutInflater.inflate(TEST_DECOR_LAYOUT_ID, parent)
            return@then parent.getChildAt(parent.childCount - 1)
        }
    }

    @Test
    fun testAnyBoundsPositionShallNoExceptionForConstructor() {
        OverlayWindow(layoutInflater, DisplayCutout.BOUNDS_POSITION_LEFT)
        OverlayWindow(layoutInflater, DisplayCutout.BOUNDS_POSITION_TOP)
        OverlayWindow(layoutInflater, DisplayCutout.BOUNDS_POSITION_RIGHT)
        OverlayWindow(layoutInflater, DisplayCutout.BOUNDS_POSITION_BOTTOM)
    }

    @Test
    fun testAddProvider() {
        @Surface.Rotation val rotation = Surface.ROTATION_270
        overlay.addDecorProvider(decorProvider, rotation)
        verify(decorProvider, Mockito.times(1)).inflateView(
                eq(layoutInflater), eq(overlay.rootView), eq(rotation))
        val viewFoundFromRootView = overlay.rootView.findViewById<View>(TEST_DECOR_VIEW_ID)
        Assert.assertNotNull(viewFoundFromRootView)
        Assert.assertEquals(viewFoundFromRootView, overlay.getView(TEST_DECOR_VIEW_ID))
    }

    @Test
    fun testRemoveView() {
        @Surface.Rotation val rotation = Surface.ROTATION_270
        overlay.addDecorProvider(decorProvider, rotation)
        overlay.removeView(TEST_DECOR_VIEW_ID)
        val viewFoundFromRootView = overlay.rootView.findViewById<View>(TEST_DECOR_VIEW_ID)
        Assert.assertNull(viewFoundFromRootView)
        Assert.assertNull(overlay.getView(TEST_DECOR_LAYOUT_ID))
    }
}