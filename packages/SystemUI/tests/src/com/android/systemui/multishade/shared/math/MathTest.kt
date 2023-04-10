/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.multishade.shared.math

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class MathTest : SysuiTestCase() {

    @Test
    fun isZero_zero_true() {
        assertThat(0f.isZero(epsilon = EPSILON)).isTrue()
    }

    @Test
    fun isZero_belowPositiveEpsilon_true() {
        assertThat((EPSILON * 0.999999f).isZero(epsilon = EPSILON)).isTrue()
    }

    @Test
    fun isZero_aboveNegativeEpsilon_true() {
        assertThat((EPSILON * -0.999999f).isZero(epsilon = EPSILON)).isTrue()
    }

    @Test
    fun isZero_positiveEpsilon_false() {
        assertThat(EPSILON.isZero(epsilon = EPSILON)).isFalse()
    }

    @Test
    fun isZero_negativeEpsilon_false() {
        assertThat((-EPSILON).isZero(epsilon = EPSILON)).isFalse()
    }

    @Test
    fun isZero_positive_false() {
        assertThat(1f.isZero(epsilon = EPSILON)).isFalse()
    }

    @Test
    fun isZero_negative_false() {
        assertThat((-1f).isZero(epsilon = EPSILON)).isFalse()
    }

    companion object {
        private const val EPSILON = 0.0001f
    }
}
