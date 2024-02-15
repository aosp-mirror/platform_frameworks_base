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
package com.android.dream.lowlight.util

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.app.animation.Interpolators
import com.google.common.truth.Truth
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class TruncatedInterpolatorTest {
    @Test
    fun truncatedInterpolator_matchesRegularInterpolator() {
        val originalInterpolator = Interpolators.EMPHASIZED
        val truncatedInterpolator =
            TruncatedInterpolator(originalInterpolator, ORIGINAL_DURATION_MS, NEW_DURATION_MS)

        // Both interpolators should start at the same value.
        var animationPercent = 0f
        Truth.assertThat(truncatedInterpolator.getInterpolation(animationPercent))
            .isEqualTo(originalInterpolator.getInterpolation(animationPercent))

        animationPercent = 1f
        Truth.assertThat(truncatedInterpolator.getInterpolation(animationPercent))
            .isEqualTo(originalInterpolator.getInterpolation(animationPercent * DURATION_RATIO))

        animationPercent = 0.25f
        Truth.assertThat(truncatedInterpolator.getInterpolation(animationPercent))
            .isEqualTo(originalInterpolator.getInterpolation(animationPercent * DURATION_RATIO))
    }

    companion object {
        private const val ORIGINAL_DURATION_MS: Float = 1000f
        private const val NEW_DURATION_MS: Float = 200f
        private const val DURATION_RATIO: Float = NEW_DURATION_MS / ORIGINAL_DURATION_MS
    }
}
