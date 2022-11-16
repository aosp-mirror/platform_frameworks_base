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
package com.android.systemui.surfaceeffects.turbulencenoise

import android.testing.AndroidTestingRunner
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class TurbulenceNoiseViewTest : SysuiTestCase() {

    private val fakeSystemClock = FakeSystemClock()
    // FakeExecutor is needed to run animator.
    private val fakeExecutor = FakeExecutor(fakeSystemClock)

    @Test
    fun play_viewHasCorrectVisibility() {
        val config = TurbulenceNoiseAnimationConfig(duration = 1000f)
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)

        assertThat(turbulenceNoiseView.visibility).isEqualTo(View.INVISIBLE)

        fakeExecutor.execute {
            turbulenceNoiseView.play(config)

            assertThat(turbulenceNoiseView.visibility).isEqualTo(View.VISIBLE)

            fakeSystemClock.advanceTime(config.duration.toLong())

            assertThat(turbulenceNoiseView.visibility).isEqualTo(View.INVISIBLE)
        }
    }

    @Test
    fun play_playsAnimation() {
        val config = TurbulenceNoiseAnimationConfig(duration = 1000f)
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)

        fakeExecutor.execute {
            turbulenceNoiseView.play(config)

            assertThat(turbulenceNoiseView.isPlaying).isTrue()
        }
    }

    @Test
    fun play_onEnd_triggersOnAnimationEnd() {
        var animationEnd = false
        val config =
            TurbulenceNoiseAnimationConfig(
                duration = 1000f,
                onAnimationEnd = { animationEnd = true }
            )
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)

        fakeExecutor.execute {
            turbulenceNoiseView.play(config)

            assertThat(turbulenceNoiseView.isPlaying).isTrue()

            fakeSystemClock.advanceTime(config.duration.toLong())

            assertThat(animationEnd).isTrue()
        }
    }
}
