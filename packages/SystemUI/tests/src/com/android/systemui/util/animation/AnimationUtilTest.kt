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

package com.android.systemui.util.animation

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.IllegalArgumentException

@SmallTest
class AnimationUtilTest : SysuiTestCase() {
    @Test
    fun getMsForFrames_5frames_returns83() {
        assertThat(AnimationUtil.getMsForFrames(5)).isEqualTo(83L)
    }

    @Test
    fun getMsForFrames_7frames_returns117() {
        assertThat(AnimationUtil.getMsForFrames(7)).isEqualTo(117L)
    }

    @Test
    fun getMsForFrames_30frames_returns500() {
        assertThat(AnimationUtil.getMsForFrames(30)).isEqualTo(500L)
    }

    @Test
    fun getMsForFrames_60frames_returns1000() {
        assertThat(AnimationUtil.getMsForFrames(60)).isEqualTo(1000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun getMsForFrames_negativeFrames_throwsException() {
        AnimationUtil.getMsForFrames(-1)
    }
}
