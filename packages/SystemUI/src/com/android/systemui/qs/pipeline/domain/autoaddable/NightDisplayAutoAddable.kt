/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.Context
import android.hardware.display.ColorDisplayManager
import android.hardware.display.NightDisplayListener
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.NightDisplayListenerModule
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.NightDisplayTile
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * [AutoAddable] for [NightDisplayTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when night display is enabled or when the auto mode changes
 * to one that supports night display.
 */
@SysUISingleton
class NightDisplayAutoAddable
@Inject
constructor(
    private val nightDisplayListenerBuilder: NightDisplayListenerModule.Builder,
    context: Context,
) : AutoAddable {

    private val enabled = ColorDisplayManager.isNightDisplayAvailable(context)
    private val spec = TileSpec.create(NightDisplayTile.TILE_SPEC)

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return conflatedCallbackFlow {
            val nightDisplayListener = nightDisplayListenerBuilder.setUser(userId).build()

            val callback =
                object : NightDisplayListener.Callback {
                    override fun onActivated(activated: Boolean) {
                        if (activated) {
                            sendAdd()
                        }
                    }

                    override fun onAutoModeChanged(autoMode: Int) {
                        if (
                            autoMode == ColorDisplayManager.AUTO_MODE_CUSTOM_TIME ||
                                autoMode == ColorDisplayManager.AUTO_MODE_TWILIGHT
                        ) {
                            sendAdd()
                        }
                    }

                    private fun sendAdd() {
                        trySend(AutoAddSignal.Add(spec))
                    }
                }

            nightDisplayListener.setCallback(callback)

            awaitClose { nightDisplayListener.setCallback(null) }
        }
    }

    override val autoAddTracking =
        if (enabled) {
            AutoAddTracking.IfNotAdded(spec)
        } else {
            AutoAddTracking.Disabled
        }

    override val description = "NightDisplayAutoAddable ($autoAddTracking)"
}
