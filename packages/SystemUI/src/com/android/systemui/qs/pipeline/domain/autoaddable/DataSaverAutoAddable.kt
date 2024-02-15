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
import com.android.systemui.qs.tiles.DataSaverTile
import com.android.systemui.statusbar.policy.DataSaverController
import javax.inject.Inject
import kotlinx.coroutines.channels.ProducerScope

/**
 * [AutoAddable] for [DataSaverTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when data saver is enabled.
 */
@SysUISingleton
class DataSaverAutoAddable
@Inject
constructor(
    dataSaverController: DataSaverController,
) :
    CallbackControllerAutoAddable<DataSaverController.Listener, DataSaverController>(
        dataSaverController
    ) {

    override val spec
        get() = TileSpec.create(DataSaverTile.TILE_SPEC)

    override fun ProducerScope<AutoAddSignal>.getCallback(): DataSaverController.Listener {
        return DataSaverController.Listener { enabled ->
            if (enabled) {
                sendAdd()
            }
        }
    }

    override val description = "DataSaverAutoAddable ($autoAddTracking)"
}
