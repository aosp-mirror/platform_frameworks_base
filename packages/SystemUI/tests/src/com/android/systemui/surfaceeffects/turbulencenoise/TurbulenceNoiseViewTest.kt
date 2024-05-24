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

import android.graphics.Color
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE_FRACTAL
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
    fun play_playsAnimation() {
        val config = TurbulenceNoiseAnimationConfig()
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)
        turbulenceNoiseView.initShader(SIMPLEX_NOISE, config)
        var onAnimationEndCalled = false

        fakeExecutor.execute {
            turbulenceNoiseView.play(onAnimationEnd = { onAnimationEndCalled = true })

            fakeSystemClock.advanceTime(config.maxDuration.toLong())

            assertThat(onAnimationEndCalled).isTrue()
        }
    }

    @Test
    fun playEaseIn_playsEaseInAnimation() {
        val config = TurbulenceNoiseAnimationConfig()
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)
        turbulenceNoiseView.initShader(SIMPLEX_NOISE, config)
        var onAnimationEndCalled = false

        fakeExecutor.execute {
            turbulenceNoiseView.playEaseIn(onAnimationEnd = { onAnimationEndCalled = true })

            fakeSystemClock.advanceTime(config.easeInDuration.toLong())

            assertThat(onAnimationEndCalled).isTrue()
        }
    }

    @Test
    fun playEaseOut_playsEaseOutAnimation() {
        val config = TurbulenceNoiseAnimationConfig()
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)
        turbulenceNoiseView.initShader(SIMPLEX_NOISE, config)
        var onAnimationEndCalled = false

        fakeExecutor.execute {
            turbulenceNoiseView.playEaseOut(onAnimationEnd = { onAnimationEndCalled = true })

            fakeSystemClock.advanceTime(config.easeOutDuration.toLong())

            assertThat(onAnimationEndCalled).isTrue()
        }
    }

    @Test
    fun finish_animationPlaying_finishesAnimation() {
        val config = TurbulenceNoiseAnimationConfig()
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)
        turbulenceNoiseView.initShader(SIMPLEX_NOISE, config)
        var onAnimationEndCalled = false

        fakeExecutor.execute {
            turbulenceNoiseView.play(onAnimationEnd = { onAnimationEndCalled = true })

            assertThat(turbulenceNoiseView.currentAnimator).isNotNull()

            turbulenceNoiseView.finish()

            assertThat(onAnimationEndCalled).isTrue()
            assertThat(turbulenceNoiseView.currentAnimator).isNull()
        }
    }

    @Test
    fun initShader_createsShaderCorrectly() {
        val config = TurbulenceNoiseAnimationConfig()
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)

        // To begin with, the shader is not initialized yet.
        assertThat(turbulenceNoiseView.turbulenceNoiseShader).isNull()

        turbulenceNoiseView.initShader(baseType = SIMPLEX_NOISE, config)

        assertThat(turbulenceNoiseView.turbulenceNoiseShader).isNotNull()
        assertThat(turbulenceNoiseView.turbulenceNoiseShader!!.baseType).isEqualTo(SIMPLEX_NOISE)
    }

    @Test
    fun initShader_changesConfig_doesNotCreateNewShader() {
        val config = TurbulenceNoiseAnimationConfig(color = Color.RED)
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)
        turbulenceNoiseView.initShader(baseType = SIMPLEX_NOISE, config)

        val shader = turbulenceNoiseView.turbulenceNoiseShader
        assertThat(shader).isNotNull()

        val newConfig = TurbulenceNoiseAnimationConfig(color = Color.GREEN)
        turbulenceNoiseView.initShader(baseType = SIMPLEX_NOISE, newConfig)

        val newShader = turbulenceNoiseView.turbulenceNoiseShader
        assertThat(newShader).isNotNull()
        assertThat(newShader).isEqualTo(shader)
    }

    @Test
    fun initShader_changesBaseType_createsNewShader() {
        val config = TurbulenceNoiseAnimationConfig()
        val turbulenceNoiseView = TurbulenceNoiseView(context, null)
        turbulenceNoiseView.initShader(baseType = SIMPLEX_NOISE, config)

        val shader = turbulenceNoiseView.turbulenceNoiseShader
        assertThat(shader).isNotNull()

        turbulenceNoiseView.initShader(baseType = SIMPLEX_NOISE_FRACTAL, config)

        val newShader = turbulenceNoiseView.turbulenceNoiseShader
        assertThat(newShader).isNotNull()
        assertThat(newShader).isNotEqualTo(shader)
    }
}
