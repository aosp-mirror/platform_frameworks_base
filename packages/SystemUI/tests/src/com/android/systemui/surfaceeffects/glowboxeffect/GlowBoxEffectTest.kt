/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.surfaceeffects.glowboxeffect

import android.graphics.Color
import android.graphics.Paint
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.surfaceeffects.PaintDrawCallback
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class GlowBoxEffectTest : SysuiTestCase() {

    @get:Rule val animatorTestRule = AnimatorTestRule(this)
    private lateinit var config: GlowBoxConfig
    private lateinit var glowBoxEffect: GlowBoxEffect
    private lateinit var drawCallback: PaintDrawCallback

    @Before
    fun setup() {
        drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {}
            }
        config =
            GlowBoxConfig(
                startCenterX = 0f,
                startCenterY = 0f,
                endCenterX = 0f,
                endCenterY = 0f,
                width = 1f,
                height = 1f,
                color = Color.WHITE,
                blurAmount = 0.1f,
                duration = 100L,
                easeInDuration = 100L,
                easeOutDuration = 100L
            )
        glowBoxEffect = GlowBoxEffect(config, drawCallback)
    }

    @Test
    fun play_paintCallback_triggersDrawCallback() {
        var paintFromCallback: Paint? = null
        drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {
                    paintFromCallback = paint
                }
            }
        glowBoxEffect = GlowBoxEffect(config, drawCallback)

        assertThat(paintFromCallback).isNull()

        glowBoxEffect.play()
        animatorTestRule.advanceTimeBy(50L)

        assertThat(paintFromCallback).isNotNull()
    }

    @Test
    fun play_followsAnimationStateInOrder() {
        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.NOT_PLAYING)

        glowBoxEffect.play()

        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.EASE_IN)

        animatorTestRule.advanceTimeBy(config.easeInDuration + 50L)

        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.MAIN)

        animatorTestRule.advanceTimeBy(config.duration + 50L)

        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.EASE_OUT)

        animatorTestRule.advanceTimeBy(config.easeOutDuration + 50L)

        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.NOT_PLAYING)
    }

    @Test
    fun finish_statePlaying_finishesAnimation() {
        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.NOT_PLAYING)

        glowBoxEffect.play()
        glowBoxEffect.finish()

        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.EASE_OUT)
    }

    @Test
    fun finish_stateNotPlaying_doesNotFinishAnimation() {
        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.NOT_PLAYING)

        glowBoxEffect.finish()

        assertThat(glowBoxEffect.state).isEqualTo(GlowBoxEffect.AnimationState.NOT_PLAYING)
    }
}
