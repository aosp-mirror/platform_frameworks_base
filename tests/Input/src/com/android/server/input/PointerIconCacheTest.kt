/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.input

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.Display
import android.view.PointerIcon
import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Tests for {@link PointerIconCache}.
 */
@Presubmit
class PointerIconCacheTest {

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var native: NativeInputManagerService
    @Mock
    private lateinit var defaultDisplay: Display

    private lateinit var context: Context
    private lateinit var testLooper: TestLooper
    private lateinit var cache: PointerIconCache

    @Before
    fun setup() {
        whenever(defaultDisplay.displayId).thenReturn(Display.DEFAULT_DISPLAY)

        context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().context) {
            override fun getDisplay() = defaultDisplay
        }

        testLooper = TestLooper()
        cache = PointerIconCache(context, native, Handler(testLooper.looper))
    }

    @Test
    fun testSetPointerScale() {
        val defaultBitmap = getDefaultIcon().bitmap
        cache.setPointerScale(2f)

        testLooper.dispatchAll()
        verify(native).reloadPointerIcons()

        val bitmap =
            cache.getLoadedPointerIcon(Display.DEFAULT_DISPLAY, PointerIcon.TYPE_ARROW).bitmap

        assertEquals(defaultBitmap.height * 2, bitmap.height)
        assertEquals(defaultBitmap.width * 2, bitmap.width)
    }

    @Test
    fun testSetAccessibilityScaleFactor() {
        val defaultBitmap = getDefaultIcon().bitmap
        cache.setAccessibilityScaleFactor(Display.DEFAULT_DISPLAY, 4f)

        testLooper.dispatchAll()
        verify(native).reloadPointerIcons()

        val bitmap =
            cache.getLoadedPointerIcon(Display.DEFAULT_DISPLAY, PointerIcon.TYPE_ARROW).bitmap

        assertEquals(defaultBitmap.height * 4, bitmap.height)
        assertEquals(defaultBitmap.width * 4, bitmap.width)
    }

    @Test
    fun testSetAccessibilityScaleFactorOnSecondaryDisplay() {
        val defaultBitmap = getDefaultIcon().bitmap
        val secondaryDisplayId = Display.DEFAULT_DISPLAY + 1
        cache.setAccessibilityScaleFactor(secondaryDisplayId, 4f)

        testLooper.dispatchAll()
        verify(native).reloadPointerIcons()

        val bitmap =
            cache.getLoadedPointerIcon(Display.DEFAULT_DISPLAY, PointerIcon.TYPE_ARROW).bitmap
        assertEquals(defaultBitmap.height, bitmap.height)
        assertEquals(defaultBitmap.width, bitmap.width)

        val bitmapSecondary =
            cache.getLoadedPointerIcon(secondaryDisplayId, PointerIcon.TYPE_ARROW).bitmap
        assertEquals(defaultBitmap.height * 4, bitmapSecondary.height)
        assertEquals(defaultBitmap.width * 4, bitmapSecondary.width)
    }

    @Test
    fun testSetPointerScaleAndAccessibilityScaleFactor() {
        val defaultBitmap = getDefaultIcon().bitmap
        cache.setPointerScale(2f)
        cache.setAccessibilityScaleFactor(Display.DEFAULT_DISPLAY, 3f)

        testLooper.dispatchAll()
        verify(native, times(2)).reloadPointerIcons()

        val bitmap =
            cache.getLoadedPointerIcon(Display.DEFAULT_DISPLAY, PointerIcon.TYPE_ARROW).bitmap

        assertEquals(defaultBitmap.height * 6, bitmap.height)
        assertEquals(defaultBitmap.width * 6, bitmap.width)
    }

    private fun getDefaultIcon() =
        PointerIcon.getLoadedSystemIcon(context, PointerIcon.TYPE_ARROW, false, 1f)
}
