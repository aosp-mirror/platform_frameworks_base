/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs.tiles.viewmodel

import com.android.internal.util.Preconditions
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory
import javax.inject.Inject

interface QSTileConfigProvider {

    /**
     * Returns a [QSTileConfig] for a [tileSpec]:
     * - injected config for [TileSpec.PlatformTileSpec] or throws [IllegalArgumentException] if
     *   there is none
     * - new config for [TileSpec.CustomTileSpec].
     * - throws [IllegalArgumentException] for [TileSpec.Invalid]
     */
    fun getConfig(tileSpec: String): QSTileConfig

    fun hasConfig(tileSpec: String): Boolean
}

@SysUISingleton
class QSTileConfigProviderImpl
@Inject
constructor(
    private val configs: Map<String, QSTileConfig>,
    private val qsEventLogger: QsEventLogger,
) : QSTileConfigProvider {

    init {
        for (entry in configs.entries) {
            val configTileSpec = entry.value.tileSpec.spec
            val keyTileSpec = entry.key
            Preconditions.checkArgument(
                configTileSpec == keyTileSpec,
                "A wrong config is injected keySpec=$keyTileSpec configSpec=$configTileSpec"
            )
        }
    }

    override fun hasConfig(tileSpec: String): Boolean =
        when (TileSpec.create(tileSpec)) {
            is TileSpec.PlatformTileSpec -> configs.containsKey(tileSpec)
            is TileSpec.CustomTileSpec -> true
            is TileSpec.Invalid -> false
        }

    override fun getConfig(tileSpec: String): QSTileConfig =
        when (val spec = TileSpec.create(tileSpec)) {
            is TileSpec.PlatformTileSpec -> {
                configs[tileSpec]
                    ?: throw IllegalArgumentException("There is no config for spec=$tileSpec")
            }
            is TileSpec.CustomTileSpec ->
                QSTileConfig(
                    spec,
                    QSTileUIConfig.Empty,
                    qsEventLogger.getNewInstanceId(),
                    category = TileCategory.PROVIDED_BY_APP,
                )
            is TileSpec.Invalid ->
                throw IllegalArgumentException("TileSpec.Invalid doesn't support configs")
        }
}
