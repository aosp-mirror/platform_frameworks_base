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
import kotlinx.coroutines.DisposableHandle

class QSLongPressEffectViewBinder {

    private var handle: DisposableHandle? = null
    val isBound: Boolean
        get() = handle != null

    fun bind(
        tile: QSTileViewImpl,
        tileSpec: String?,
        effect: QSLongPressEffect?,
    ) {
        if (effect == null) return

        handle =
            tile.repeatWhenAttached {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    effect.scope = this
                    val tag = "${tileSpec ?: "unknownTileSpec"}#LongPressEffect"

                    launch("$tag#progress") {
                        effect.effectProgress.collect { progress ->
                            progress?.let {
                                if (it == 0f) {
                                    tile.bringToFront()
                                }
                                tile.updateLongPressEffectProperties(it)
                            }
                        }
                    }

                    launch("$tag#action") {
                        effect.actionType.collect { action ->
                            action?.let {
                                when (it) {
                                    QSLongPressEffect.ActionType.CLICK -> tile.performClick()
                                    QSLongPressEffect.ActionType.LONG_PRESS ->
                                        tile.performLongClick()
                                }
                                effect.clearActionType()
                            }
                        }
                    }
                }
            }
    }

    fun dispose() {
        handle?.dispose()
        handle = null
    }
}
