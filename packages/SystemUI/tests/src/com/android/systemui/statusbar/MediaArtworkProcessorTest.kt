/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar

import com.google.common.truth.Truth.assertThat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Point
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val WIDTH = 200
private const val HEIGHT = 200

@RunWith(AndroidTestingRunner::class)
@SmallTest
class MediaArtworkProcessorTest : SysuiTestCase() {

    private var screenWidth = 0
    private var screenHeight = 0

    private lateinit var processor: MediaArtworkProcessor

    @Before
    fun setUp() {
        processor = MediaArtworkProcessor()

        val point = Point()
        context.display.getSize(point)
        screenWidth = point.x
        screenHeight = point.y
    }

    @After
    fun tearDown() {
        processor.clearCache()
    }

    @Test
    fun testProcessArtwork() {
        // GIVEN some "artwork", which is just a solid blue image
        val artwork = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(artwork).drawColor(Color.BLUE)
        // WHEN the background is created from the artwork
        val background = processor.processArtwork(context, artwork)!!
        // THEN the background has the size of the screen that has been downsamples
        assertThat(background.height).isLessThan(screenHeight)
        assertThat(background.width).isLessThan(screenWidth)
        assertThat(background.config).isEqualTo(Bitmap.Config.ARGB_8888)
    }

    @Test
    fun testCache() {
        // GIVEN a solid blue image
        val artwork = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(artwork).drawColor(Color.BLUE)
        // WHEN the background is processed twice
        val background1 = processor.processArtwork(context, artwork)!!
        val background2 = processor.processArtwork(context, artwork)!!
        // THEN the two bitmaps are the same
        // Note: This is currently broken and trying to use caching causes issues
        assertThat(background1).isNotSameInstanceAs(background2)
    }

    @Test
    fun testConfig() {
        // GIVEN some which is not ARGB_8888
        val artwork = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ALPHA_8)
        Canvas(artwork).drawColor(Color.BLUE)
        // WHEN the background is created from the artwork
        val background = processor.processArtwork(context, artwork)!!
        // THEN the background has Config ARGB_8888
        assertThat(background.config).isEqualTo(Bitmap.Config.ARGB_8888)
    }

    @Test
    fun testRecycledArtwork() {
        // GIVEN some "artwork", which is just a solid blue image
        val artwork = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        Canvas(artwork).drawColor(Color.BLUE)
        // AND the artwork is recycled
        artwork.recycle()
        // WHEN the background is created from the artwork
        val background = processor.processArtwork(context, artwork)
        // THEN the processed bitmap is null
        assertThat(background).isNull()
    }
}