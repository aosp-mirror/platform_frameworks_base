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

package com.android.systemui.surfaceeffects.ripple

import android.graphics.Color
import android.testing.AndroidTestingRunner
import androidx.core.graphics.ColorUtils
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class RippleAnimationTest : SysuiTestCase() {

    private val fakeSystemClock = FakeSystemClock()
    private val fakeExecutor = FakeExecutor(fakeSystemClock)

    @Test
    fun init_shaderHasCorrectConfig() {
        val config =
            RippleAnimationConfig(
                duration = 3000L,
                pixelDensity = 2f,
                color = Color.RED,
                opacity = 30,
                shouldFillRipple = true,
                sparkleStrength = 0.3f
            )
        val rippleAnimation = RippleAnimation(config)

        with(rippleAnimation.rippleShader) {
            assertThat(rippleFill).isEqualTo(config.shouldFillRipple)
            assertThat(pixelDensity).isEqualTo(config.pixelDensity)
            assertThat(color).isEqualTo(ColorUtils.setAlphaComponent(config.color, config.opacity))
            assertThat(sparkleStrength).isEqualTo(config.sparkleStrength)
        }
    }

    @Test
    fun updateColor_updatesColorCorrectly() {
        val initialColor = Color.WHITE
        val expectedColor = Color.RED
        val config = RippleAnimationConfig(color = initialColor)
        val rippleAnimation = RippleAnimation(config)

        fakeExecutor.execute {
            with(rippleAnimation) {
                play()
                updateColor(expectedColor)
            }

            assertThat(config.color).isEqualTo(expectedColor)
        }
    }

    @Test
    fun play_updatesIsPlaying() {
        val config = RippleAnimationConfig(duration = 1000L)
        val rippleAnimation = RippleAnimation(config)

        fakeExecutor.execute {
            rippleAnimation.play()

            assertThat(rippleAnimation.isPlaying()).isTrue()

            // move time to finish the animation
            fakeSystemClock.advanceTime(config.duration)

            assertThat(rippleAnimation.isPlaying()).isFalse()
        }
    }

    @Test
    fun play_onEnd_triggersOnAnimationEnd() {
        val config = RippleAnimationConfig(duration = 1000L)
        val rippleAnimation = RippleAnimation(config)
        var animationEnd = false

        fakeExecutor.execute {
            rippleAnimation.play(onAnimationEnd = { animationEnd = true })

            fakeSystemClock.advanceTime(config.duration)

            assertThat(animationEnd).isTrue()
        }
    }
}
