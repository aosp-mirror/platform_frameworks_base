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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.surfaceeffects.ripple.MultiRippleController.Companion.MAX_RIPPLE_NUMBER
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class MultiRippleControllerTest : SysuiTestCase() {
    private lateinit var multiRippleController: MultiRippleController
    private lateinit var multiRippleView: MultiRippleView
    private lateinit var rippleAnimationConfig: RippleAnimationConfig
    private val fakeSystemClock = FakeSystemClock()

    // FakeExecutor is needed to run animator.
    private val fakeExecutor = FakeExecutor(fakeSystemClock)

    @Before
    fun setup() {
        rippleAnimationConfig = RippleAnimationConfig(duration = 1000L)
        multiRippleView = MultiRippleView(context, null)
        multiRippleController = MultiRippleController(multiRippleView)
    }

    @Test
    fun updateColor_updatesColor() {
        val initialColor = Color.WHITE
        val expectedColor = Color.RED

        fakeExecutor.execute {
            val rippleAnimation =
                RippleAnimation(rippleAnimationConfig.apply { this.color = initialColor })

            with(multiRippleController) {
                play(rippleAnimation)
                updateColor(expectedColor)
            }

            assertThat(rippleAnimationConfig.color).isEqualTo(expectedColor)
        }
    }

    @Test
    fun play_playsRipple() {
        fakeExecutor.execute {
            val rippleAnimation = RippleAnimation(rippleAnimationConfig)

            multiRippleController.play(rippleAnimation)

            assertThat(multiRippleView.ripples.size).isEqualTo(1)
            assertThat(multiRippleView.ripples[0]).isEqualTo(rippleAnimation)
        }
    }

    @Test
    fun play_doesNotExceedMaxRipple() {
        fakeExecutor.execute {
            for (i in 0..MAX_RIPPLE_NUMBER + 10) {
                multiRippleController.play(RippleAnimation(rippleAnimationConfig))
            }

            assertThat(multiRippleView.ripples.size).isEqualTo(MAX_RIPPLE_NUMBER)
        }
    }

    @Test
    fun play_onEnd_removesAnimation() {
        fakeExecutor.execute {
            val rippleAnimation = RippleAnimation(rippleAnimationConfig)
            multiRippleController.play(rippleAnimation)

            assertThat(multiRippleView.ripples.size).isEqualTo(1)
            assertThat(multiRippleView.ripples[0]).isEqualTo(rippleAnimation)

            fakeSystemClock.advanceTime(rippleAnimationConfig.duration)

            assertThat(multiRippleView.ripples.size).isEqualTo(0)
        }
    }
}
