/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import android.testing.TestableLooper.RunWithLooper
import android.text.PrecomputedText
import android.text.TextPaint
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class TextPrecomputerTest : SysuiTestCase() {

    private lateinit var textPrecomputer: TextPrecomputer

    private lateinit var textView: TextView

    @Before
    fun before() {
        textPrecomputer = object : TextPrecomputer {}
        textView = TextView(mContext)
    }

    @Test
    fun precompute_returnRunnable() {
        // WHEN
        val precomputeResult = textPrecomputer.precompute(textView, TEXT)

        // THEN
        assertThat(precomputeResult).isInstanceOf(Runnable::class.java)
    }

    @Test
    fun precomputeRunnable_anyText_setPrecomputedText() {
        // WHEN
        textPrecomputer.precompute(textView, TEXT).run()

        // THEN
        assertThat(textView.text).isInstanceOf(PrecomputedText::class.java)
    }

    @Test
    fun precomputeRunnable_differentPrecomputedTextConfig_notSetPrecomputedText() {
        // GIVEN
        val precomputedTextRunnable =
            textPrecomputer.precompute(textView, TEXT, logException = false)

        // WHEN
        textView.textMetricsParams = PrecomputedText.Params.Builder(PAINT).build()
        precomputedTextRunnable.run()

        // THEN
        assertThat(textView.text).isInstanceOf(String::class.java)
    }

    @Test
    fun precomputeRunnable_nullText_setNull() {
        // GIVEN
        textView.text = TEXT
        val precomputedTextRunnable = textPrecomputer.precompute(textView, null)

        // WHEN
        precomputedTextRunnable.run()

        // THEN
        assertThat(textView.text).isEqualTo("")
    }

    private companion object {
        private val PAINT = TextPaint()
        private const val TEXT = "Example Notification Test"
    }
}
