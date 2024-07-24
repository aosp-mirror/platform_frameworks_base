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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class PinShapeHintingViewTest : SysuiTestCase() {
    lateinit var underTest: PinShapeHintingView

    @Before
    fun setup() {
        underTest =
            LayoutInflater.from(context).inflate(R.layout.keyguard_pin_shape_hinting_view, null)
                as PinShapeHintingView
    }

    @Test
    fun testAppend() {
        // Add more when animation part is complete
        underTest.append()
        Truth.assertThat(underTest.childCount).isEqualTo(6)
    }

    @Test
    fun testDelete() {
        underTest.delete()
        Truth.assertThat(underTest.childCount).isEqualTo(6)
    }

    @Test
    fun testReset() {
        for (i in 0 until 3) {
            underTest.append()
        }
        underTest.reset()
        Truth.assertThat(underTest.childCount).isEqualTo(6)
    }
}
