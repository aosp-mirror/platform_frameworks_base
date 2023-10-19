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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.CastTile
import com.android.systemui.statusbar.policy.CastController
import javax.inject.Inject
import kotlinx.coroutines.channels.ProducerScope

/**
 * [AutoAddable] for [CastTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when there's a casting device connected or connecting.
 */
@SysUISingleton
class CastAutoAddable
@Inject
constructor(
    private val controller: CastController,
) : CallbackControllerAutoAddable<CastController.Callback, CastController>(controller) {

    override val spec: TileSpec
        get() = TileSpec.create(CastTile.TILE_SPEC)

    override fun ProducerScope<AutoAddSignal>.getCallback(): CastController.Callback {
        return CastController.Callback {
            val isCasting =
                controller.castDevices.any {
                    it.state == CastController.CastDevice.STATE_CONNECTED ||
                        it.state == CastController.CastDevice.STATE_CONNECTING
                }
            if (isCasting) {
                sendAdd()
            }
        }
    }

    override val description = "CastAutoAddable ($autoAddTracking)"
}
