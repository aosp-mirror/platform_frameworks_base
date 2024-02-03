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
import com.android.systemui.qs.ReduceBrightColorsController
import com.android.systemui.qs.dagger.QSFlagsModule.RBC_AVAILABLE
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.channels.ProducerScope

/**
 * [AutoAddable] for [ReduceBrightColorsTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when reduce bright colors is enabled.
 */
@SysUISingleton
class ReduceBrightColorsAutoAddable
@Inject
constructor(
    controller: ReduceBrightColorsController,
    @Named(RBC_AVAILABLE) private val available: Boolean,
) :
    CallbackControllerAutoAddable<
        ReduceBrightColorsController.Listener, ReduceBrightColorsController
    >(controller) {

    override val spec: TileSpec
        get() = TileSpec.create(ReduceBrightColorsTile.TILE_SPEC)

    override fun ProducerScope<AutoAddSignal>.getCallback(): ReduceBrightColorsController.Listener {
        return object : ReduceBrightColorsController.Listener {
            override fun onActivated(activated: Boolean) {
                if (activated) {
                    sendAdd()
                }
            }
        }
    }

    override val autoAddTracking
        get() =
            if (available) {
                super.autoAddTracking
            } else {
                AutoAddTracking.Disabled
            }

    override val description = "ReduceBrightColorsAutoAddable ($autoAddTracking)"
}
