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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.app.tracing.coroutines.launch
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// TODO(b/332903800)
object QSLongPressEffectViewBinder {
    fun bind(
        tile: QSTileViewImpl,
        qsLongPressEffect: QSLongPressEffect?,
        tileSpec: String?,
    ): DisposableHandle? {
        if (qsLongPressEffect == null) return null

        return tile.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                val tag = "${tileSpec ?: "unknownTileSpec"}#LongPressEffect"
                // Progress of the effect
                launch("$tag#progress") {
                    qsLongPressEffect.effectProgress.collect { progress ->
                        progress?.let {
                            if (it == 0f) {
                                tile.bringToFront()
                            } else {
                                tile.updateLongPressEffectProperties(it)
                            }
                        }
                    }
                }

                // Action to perform
                launch("$tag#action") {
                    qsLongPressEffect.actionType.collect { action ->
                        action?.let {
                            when (it) {
                                QSLongPressEffect.ActionType.CLICK -> tile.performClick()
                                QSLongPressEffect.ActionType.LONG_PRESS -> tile.performLongClick()
                                QSLongPressEffect.ActionType.RESET_AND_LONG_PRESS -> {
                                    tile.resetLongPressEffectProperties()
                                    tile.performLongClick()
                                }
                            }
                            qsLongPressEffect.clearActionType()
                        }
                    }
                }

                // Tap timeout wait
                launch("$tag#timeout") {
                    qsLongPressEffect.shouldWaitForTapTimeout
                        .filter { it }
                        .collect {
                            try {
                                delay(QSLongPressEffect.PRESSED_TIMEOUT)
                                qsLongPressEffect.handleTimeoutComplete()
                            } catch (_: CancellationException) {
                                qsLongPressEffect.resetEffect()
                            }
                        }
                }
            }
        }
    }
}
