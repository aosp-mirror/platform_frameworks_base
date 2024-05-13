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

package com.android.systemui.qs.panels.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.shared.TileSpec
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Repository for retrieving the list of [TileSpec] to be displayed as icons. */
interface IconTilesRepository {
    val iconTilesSpecs: StateFlow<Set<TileSpec>>
}

@SysUISingleton
class IconTilesRepositoryImpl @Inject constructor() : IconTilesRepository {

    private val _iconTilesSpecs =
        MutableStateFlow(
            setOf(
                TileSpec.create("airplane"),
                TileSpec.create("battery"),
                TileSpec.create("cameratoggle"),
                TileSpec.create("cast"),
                TileSpec.create("color_correction"),
                TileSpec.create("inversion"),
                TileSpec.create("saver"),
                TileSpec.create("dnd"),
                TileSpec.create("flashlight"),
                TileSpec.create("location"),
                TileSpec.create("mictoggle"),
                TileSpec.create("nfc"),
                TileSpec.create("night"),
                TileSpec.create("rotation")
            )
        )

    /** Set of toggleable tiles that are suitable for being shown as an icon. */
    override val iconTilesSpecs: StateFlow<Set<TileSpec>> = _iconTilesSpecs.asStateFlow()
}
