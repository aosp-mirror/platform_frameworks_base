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

package com.android.keyguard

import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class PinShapeNonHintingViewTest : SysuiTestCase() {
    lateinit var underTest: PinShapeNonHintingView

    @Before
    fun setup() {
        underTest =
            LayoutInflater.from(context).inflate(R.layout.keyguard_pin_shape_non_hinting_view, null)
                as PinShapeNonHintingView
    }

    @Test
    fun testAppend() {
        // Add more when animation part is complete
        underTest.append()
        Truth.assertThat(underTest.childCount).isEqualTo(1)
    }

    @Test
    fun testDelete() {
        for (i in 0 until 3) {
            underTest.append()
        }
        underTest.delete()

        underTest.postDelayed(
            { Truth.assertThat(underTest.childCount).isEqualTo(2) },
            PasswordTextView.DISAPPEAR_DURATION + 100L
        )
    }

    @Test
    fun testReset() {
        for (i in 0 until 3) {
            underTest.append()
        }
        underTest.reset()
        underTest.postDelayed(
            { Truth.assertThat(underTest.childCount).isEqualTo(0) },
            PasswordTextView.DISAPPEAR_DURATION + 100L
        )
    }
}
