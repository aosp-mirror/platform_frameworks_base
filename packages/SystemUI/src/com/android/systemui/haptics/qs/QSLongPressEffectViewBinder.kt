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
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.qs.tileimpl.QSTileViewImpl
import kotlinx.coroutines.launch

object QSLongPressEffectViewBinder {

    fun bind(
        tile: QSTileViewImpl,
        effect: QSLongPressEffect?,
    ) {
        if (effect == null) return

        tile.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                effect.scope = this

                launch {
                    effect.effectProgress.collect { progress ->
                        progress?.let {
                            if (it == 0f) {
                                tile.bringToFront()
                            }
                            tile.updateLongPressEffectProperties(it)
                        }
                    }
                }

                launch {
                    effect.actionType.collect { action ->
                        action?.let {
                            when (it) {
                                QSLongPressEffect.ActionType.CLICK -> tile.performClick()
                                QSLongPressEffect.ActionType.LONG_PRESS -> tile.performLongClick()
                            }
                            effect.clearActionType()
                        }
                    }
                }
            }
        }
    }
}
