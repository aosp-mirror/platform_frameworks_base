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

package com.android.systemui.surfaceeffects.loadingeffect

import android.graphics.Paint
import android.graphics.RenderEffect
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.animation.AnimatorTestRule
import com.android.systemui.model.SysUiStateTest
import com.android.systemui.surfaceeffects.PaintDrawCallback
import com.android.systemui.surfaceeffects.RenderEffectDrawCallback
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseAnimationConfig
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseShader
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class LoadingEffectTest : SysUiStateTest() {

    @get:Rule val animatorTestRule = AnimatorTestRule(this)

    @Test
    fun play_paintCallback_triggersDrawCallback() {
        var paintFromCallback: Paint? = null
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {
                    paintFromCallback = paint
                }
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                TurbulenceNoiseAnimationConfig(),
                paintCallback = drawCallback,
                animationStateChangedCallback = null
            )

        assertThat(paintFromCallback).isNull()

        loadingEffect.play()
        animatorTestRule.advanceTimeBy(500L)

        assertThat(paintFromCallback).isNotNull()
    }

    @Test
    fun play_renderEffectCallback_triggersDrawCallback() {
        var renderEffectFromCallback: RenderEffect? = null
        val drawCallback =
            object : RenderEffectDrawCallback {
                override fun onDraw(renderEffect: RenderEffect) {
                    renderEffectFromCallback = renderEffect
                }
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                TurbulenceNoiseAnimationConfig(),
                renderEffectCallback = drawCallback,
                animationStateChangedCallback = null
            )

        assertThat(renderEffectFromCallback).isNull()

        loadingEffect.play()
        animatorTestRule.advanceTimeBy(500L)

        assertThat(renderEffectFromCallback).isNotNull()
    }

    @Test
    fun play_animationStateChangesInOrder() {
        val config = TurbulenceNoiseAnimationConfig()
        val states = mutableListOf(LoadingEffect.AnimationState.NOT_PLAYING)
        val stateChangedCallback =
            object : LoadingEffect.AnimationStateChangedCallback {
                override fun onStateChanged(
                    oldState: LoadingEffect.AnimationState,
                    newState: LoadingEffect.AnimationState
                ) {
                    states.add(newState)
                }
            }
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {}
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                stateChangedCallback
            )

        loadingEffect.play()

        // Execute all the animators by advancing each duration with some buffer.
        animatorTestRule.advanceTimeBy(config.easeInDuration.toLong())
        animatorTestRule.advanceTimeBy(config.maxDuration.toLong())
        animatorTestRule.advanceTimeBy(config.easeOutDuration.toLong())
        animatorTestRule.advanceTimeBy(500)

        assertThat(states)
            .containsExactly(
                LoadingEffect.AnimationState.NOT_PLAYING,
                LoadingEffect.AnimationState.EASE_IN,
                LoadingEffect.AnimationState.MAIN,
                LoadingEffect.AnimationState.EASE_OUT,
                LoadingEffect.AnimationState.NOT_PLAYING
            )
    }

    @Test
    fun play_alreadyPlaying_playsOnlyOnce() {
        val config = TurbulenceNoiseAnimationConfig()
        var numPlay = 0
        val stateChangedCallback =
            object : LoadingEffect.AnimationStateChangedCallback {
                override fun onStateChanged(
                    oldState: LoadingEffect.AnimationState,
                    newState: LoadingEffect.AnimationState
                ) {
                    if (
                        oldState == LoadingEffect.AnimationState.NOT_PLAYING &&
                            newState == LoadingEffect.AnimationState.EASE_IN
                    ) {
                        numPlay++
                    }
                }
            }
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {}
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                stateChangedCallback
            )

        assertThat(numPlay).isEqualTo(0)

        loadingEffect.play()
        loadingEffect.play()
        loadingEffect.play()
        loadingEffect.play()
        loadingEffect.play()

        assertThat(numPlay).isEqualTo(1)
    }

    @Test
    fun finish_finishesLoadingEffect() {
        val config = TurbulenceNoiseAnimationConfig(maxDuration = 1000f)
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(loadingPaint: Paint) {}
            }
        var isFinished = false
        val stateChangedCallback =
            object : LoadingEffect.AnimationStateChangedCallback {
                override fun onStateChanged(
                    oldState: LoadingEffect.AnimationState,
                    newState: LoadingEffect.AnimationState
                ) {
                    if (
                        oldState == LoadingEffect.AnimationState.EASE_OUT &&
                            newState == LoadingEffect.AnimationState.NOT_PLAYING
                    ) {
                        isFinished = true
                    }
                }
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                stateChangedCallback
            )

        assertThat(isFinished).isFalse()

        loadingEffect.play()
        animatorTestRule.advanceTimeBy(config.easeInDuration.toLong() + 500L)

        assertThat(isFinished).isFalse()

        loadingEffect.finish()
        animatorTestRule.advanceTimeBy(config.easeOutDuration.toLong() + 500L)

        assertThat(isFinished).isTrue()
    }

    @Test
    fun finish_notMainState_hasNoEffect() {
        val config = TurbulenceNoiseAnimationConfig(maxDuration = 1000f)
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {}
            }
        var isFinished = false
        val stateChangedCallback =
            object : LoadingEffect.AnimationStateChangedCallback {
                override fun onStateChanged(
                    oldState: LoadingEffect.AnimationState,
                    newState: LoadingEffect.AnimationState
                ) {
                    if (
                        oldState == LoadingEffect.AnimationState.MAIN &&
                            newState == LoadingEffect.AnimationState.NOT_PLAYING
                    ) {
                        isFinished = true
                    }
                }
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                stateChangedCallback
            )

        assertThat(isFinished).isFalse()

        loadingEffect.finish()

        assertThat(isFinished).isFalse()
    }

    @Test
    fun getNoiseOffset_returnsNoiseOffset() {
        val expectedNoiseOffset = arrayOf(0.1f, 0.2f, 0.3f)
        val config =
            TurbulenceNoiseAnimationConfig(
                noiseOffsetX = expectedNoiseOffset[0],
                noiseOffsetY = expectedNoiseOffset[1],
                noiseOffsetZ = expectedNoiseOffset[2]
            )
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(paint: Paint) {}
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                animationStateChangedCallback = null
            )

        assertThat(loadingEffect.getNoiseOffset()).isEqualTo(expectedNoiseOffset)
    }
}
