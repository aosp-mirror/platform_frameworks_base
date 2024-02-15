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
import com.android.systemui.qs.tiles.HotspotTile
import com.android.systemui.statusbar.policy.HotspotController
import javax.inject.Inject
import kotlinx.coroutines.channels.ProducerScope

/**
 * [AutoAddable] for [HotspotTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when hotspot is enabled.
 */
@SysUISingleton
class HotspotAutoAddable
@Inject
constructor(
    hotspotController: HotspotController,
) :
    CallbackControllerAutoAddable<HotspotController.Callback, HotspotController>(
        hotspotController
    ) {

    override val spec
        get() = TileSpec.create(HotspotTile.TILE_SPEC)

    override fun ProducerScope<AutoAddSignal>.getCallback(): HotspotController.Callback {
        return HotspotController.Callback { enabled, _ ->
            if (enabled) {
                sendAdd()
            }
        }
    }

    override val description = "HotspotAutoAddable ($autoAddTracking)"
}
