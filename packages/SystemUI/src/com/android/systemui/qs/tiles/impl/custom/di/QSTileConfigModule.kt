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

package com.android.systemui.qs.tiles.impl.custom.di

import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import dagger.Module
import dagger.Provides

/**
 * Provides [QSTileConfig] and [TileSpec]. To be used along in a QS tile scoped component
 * implementing [com.android.systemui.qs.tiles.impl.di.QSTileComponent]. In that case it makes it
 * possible to inject config and tile spec associated with the current tile
 */
@Module
class QSTileConfigModule(private val config: QSTileConfig) {

    @Provides fun provideConfig(): QSTileConfig = config

    @Provides fun provideTileSpec(): TileSpec = config.tileSpec

    @Provides
    fun provideCustomTileSpec(): TileSpec.CustomTileSpec =
        config.tileSpec as TileSpec.CustomTileSpec

    @Provides
    fun providePlatformTileSpec(): TileSpec.PlatformTileSpec =
        config.tileSpec as TileSpec.PlatformTileSpec
}
