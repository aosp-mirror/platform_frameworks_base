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
 * limitations under the License.
 */

package com.android.keyguard.clock

import android.content.Context
import com.google.common.truth.Truth.assertThat

import android.graphics.Canvas
import android.graphics.Color
import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ViewPreviewerTest : SysuiTestCase() {

    private lateinit var previewer: ViewPreviewer
    private lateinit var view: View

    @Before
    fun setUp() {
        previewer = ViewPreviewer()
        view = TestView(context)
    }

    @Test
    fun testCreatePreview() {
        val width = 100
        val height = 100
        // WHEN a preview image is created
        val bitmap = previewer.createPreview(view, width, height)!!
        // THEN the bitmap has the expected width and height
        assertThat(bitmap.height).isEqualTo(height)
        assertThat(bitmap.width).isEqualTo(width)
        assertThat(bitmap.getPixel(0, 0)).isEqualTo(Color.RED)
    }

    class TestView(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas?) {
            super.onDraw(canvas)
            canvas?.drawColor(Color.RED)
        }
    }
}
