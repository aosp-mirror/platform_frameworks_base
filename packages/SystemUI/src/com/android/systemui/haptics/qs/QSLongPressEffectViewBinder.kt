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

package com.android.systemui.haptics.qs

import android.animation.ValueAnimator
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

object QSLongPressEffectViewBinder {

    fun bind(
        tile: QSTileViewImpl,
        qsLongPressEffect: QSLongPressEffect?,
        tileSpec: String?,
    ): DisposableHandle? {
        if (qsLongPressEffect == null) return null

        return tile.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                // Action to perform
                launch({ "${tileSpec ?: "unknownTileSpec"}#LongPressEffect#action" }) {
                    var effectAnimator: ValueAnimator? = null

                    qsLongPressEffect.actionType.filterNotNull().collect { action ->
                        when (action) {
                            QSLongPressEffect.ActionType.WAIT_TAP_TIMEOUT -> {
                                delay(ViewConfiguration.getTapTimeout().toLong())
                                qsLongPressEffect.handleTimeoutComplete()
                            }
                            QSLongPressEffect.ActionType.LONG_PRESS -> {
                                tile.prepareForLaunch()
                                qsLongPressEffect.qsTile?.longClick(qsLongPressEffect.expandable)
                                qsLongPressEffect.clearActionType()
                            }
                            QSLongPressEffect.ActionType.RESET_AND_LONG_PRESS -> {
                                tile.resetLongPressEffectProperties()
                                qsLongPressEffect.qsTile?.longClick(qsLongPressEffect.expandable)
                                qsLongPressEffect.clearActionType()
                            }
                            QSLongPressEffect.ActionType.START_ANIMATOR -> {
                                if (effectAnimator?.isRunning != true) {
                                    effectAnimator =
                                        ValueAnimator.ofFloat(0f, 1f).apply {
                                            this.duration =
                                                qsLongPressEffect.effectDuration.toLong()
                                            interpolator = AccelerateDecelerateInterpolator()

                                            doOnStart { qsLongPressEffect.handleAnimationStart() }
                                            addUpdateListener {
                                                val value = animatedValue as Float
                                                if (value == 0f) {
                                                    tile.bringToFront()
                                                } else {
                                                    tile.updateLongPressEffectProperties(value)
                                                }
                                            }
                                            doOnEnd { qsLongPressEffect.handleAnimationComplete() }
                                            doOnCancel { qsLongPressEffect.handleAnimationCancel() }
                                            start()
                                        }
                                }
                            }
                            QSLongPressEffect.ActionType.REVERSE_ANIMATOR -> {
                                effectAnimator?.let {
                                    val pausedProgress = it.animatedFraction
                                    qsLongPressEffect.playReverseHaptics(pausedProgress)
                                    it.reverse()
                                }
                            }
                            QSLongPressEffect.ActionType.CANCEL_ANIMATOR -> {
                                tile.resetLongPressEffectProperties()
                                effectAnimator?.cancel()
                            }
                        }
                    }
                }
            }
        }
    }
}
