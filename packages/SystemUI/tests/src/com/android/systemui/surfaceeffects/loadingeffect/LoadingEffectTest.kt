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
import androidx.test.filters.SmallTest
import com.android.systemui.model.SysUiStateTest
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.AnimationState
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.AnimationState.EASE_IN
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.AnimationState.EASE_OUT
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.AnimationState.MAIN
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.AnimationState.NOT_PLAYING
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.AnimationStateChangedCallback
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.PaintDrawCallback
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect.Companion.RenderEffectDrawCallback
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseAnimationConfig
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseShader
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class LoadingEffectTest : SysUiStateTest() {

    private val fakeSystemClock = FakeSystemClock()
    private val fakeExecutor = FakeExecutor(fakeSystemClock)

    @Test
    fun play_paintCallback_triggersDrawCallback() {
        var paintFromCallback: Paint? = null
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(loadingPaint: Paint) {
                    paintFromCallback = loadingPaint
                }
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                TurbulenceNoiseAnimationConfig(),
                paintCallback = drawCallback,
                animationStateChangedCallback = null
            )

        fakeExecutor.execute {
            assertThat(paintFromCallback).isNull()

            loadingEffect.play()
            fakeSystemClock.advanceTime(500L)

            assertThat(paintFromCallback).isNotNull()
        }
    }

    @Test
    fun play_renderEffectCallback_triggersDrawCallback() {
        var renderEffectFromCallback: RenderEffect? = null
        val drawCallback =
            object : RenderEffectDrawCallback {
                override fun onDraw(loadingRenderEffect: RenderEffect) {
                    renderEffectFromCallback = loadingRenderEffect
                }
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                TurbulenceNoiseAnimationConfig(),
                renderEffectCallback = drawCallback,
                animationStateChangedCallback = null
            )

        fakeExecutor.execute {
            assertThat(renderEffectFromCallback).isNull()

            loadingEffect.play()
            fakeSystemClock.advanceTime(500L)

            assertThat(renderEffectFromCallback).isNotNull()
        }
    }

    @Test
    fun play_animationStateChangesInOrder() {
        val config = TurbulenceNoiseAnimationConfig()
        val expectedStates = arrayOf(NOT_PLAYING, EASE_IN, MAIN, EASE_OUT, NOT_PLAYING)
        val actualStates = mutableListOf(NOT_PLAYING)
        val stateChangedCallback =
            object : AnimationStateChangedCallback {
                override fun onStateChanged(oldState: AnimationState, newState: AnimationState) {
                    actualStates.add(newState)
                }
            }
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(loadingPaint: Paint) {}
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                stateChangedCallback
            )

        val timeToAdvance =
            config.easeInDuration + config.maxDuration + config.easeOutDuration + 100

        fakeExecutor.execute {
            loadingEffect.play()

            fakeSystemClock.advanceTime(timeToAdvance.toLong())

            assertThat(actualStates).isEqualTo(expectedStates)
        }
    }

    @Test
    fun play_alreadyPlaying_playsOnlyOnce() {
        val config = TurbulenceNoiseAnimationConfig()
        var numPlay = 0
        val stateChangedCallback =
            object : AnimationStateChangedCallback {
                override fun onStateChanged(oldState: AnimationState, newState: AnimationState) {
                    if (oldState == NOT_PLAYING && newState == EASE_IN) {
                        numPlay++
                    }
                }
            }
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(loadingPaint: Paint) {}
            }
        val loadingEffect =
            LoadingEffect(
                baseType = TurbulenceNoiseShader.Companion.Type.SIMPLEX_NOISE,
                config,
                paintCallback = drawCallback,
                stateChangedCallback
            )

        fakeExecutor.execute {
            assertThat(numPlay).isEqualTo(0)

            loadingEffect.play()
            loadingEffect.play()
            loadingEffect.play()
            loadingEffect.play()
            loadingEffect.play()

            assertThat(numPlay).isEqualTo(1)
        }
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
            object : AnimationStateChangedCallback {
                override fun onStateChanged(oldState: AnimationState, newState: AnimationState) {
                    if (oldState == MAIN && newState == NOT_PLAYING) {
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

        fakeExecutor.execute {
            assertThat(isFinished).isFalse()

            loadingEffect.play()
            fakeSystemClock.advanceTime(config.easeInDuration.toLong() + 500L)

            assertThat(isFinished).isFalse()

            loadingEffect.finish()

            assertThat(isFinished).isTrue()
        }
    }

    @Test
    fun finish_notMainState_hasNoEffect() {
        val config = TurbulenceNoiseAnimationConfig(maxDuration = 1000f)
        val drawCallback =
            object : PaintDrawCallback {
                override fun onDraw(loadingPaint: Paint) {}
            }
        var isFinished = false
        val stateChangedCallback =
            object : AnimationStateChangedCallback {
                override fun onStateChanged(oldState: AnimationState, newState: AnimationState) {
                    if (oldState == MAIN && newState == NOT_PLAYING) {
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

        fakeExecutor.execute {
            assertThat(isFinished).isFalse()

            loadingEffect.finish()

            assertThat(isFinished).isFalse()
        }
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
                override fun onDraw(loadingPaint: Paint) {}
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
