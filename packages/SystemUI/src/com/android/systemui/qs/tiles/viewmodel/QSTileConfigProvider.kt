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
import javax.inject.Inject

interface QSTileConfigProvider {

    /**
     * Returns a [QSTileConfig] for a [tileSpec] or throws [IllegalArgumentException] if there is no
     * config for such [tileSpec].
     */
    fun getConfig(tileSpec: String): QSTileConfig
}

@SysUISingleton
class QSTileConfigProviderImpl @Inject constructor(private val configs: Map<String, QSTileConfig>) :
    QSTileConfigProvider {

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

    override fun getConfig(tileSpec: String): QSTileConfig =
        configs[tileSpec] ?: throw IllegalArgumentException("There is no config for spec=$tileSpec")
}
