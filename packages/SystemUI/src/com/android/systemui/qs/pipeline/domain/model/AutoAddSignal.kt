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

package com.android.systemui.qs.pipeline.domain.model

import com.android.systemui.qs.pipeline.data.repository.TileSpecRepository.Companion.POSITION_AT_END
import com.android.systemui.qs.pipeline.shared.TileSpec

/** Signal indicating when a tile needs to be auto-added or removed */
sealed interface AutoAddSignal {
    /** Tile for this object */
    val spec: TileSpec

    /** Signal for auto-adding a tile at [position]. */
    data class Add(
        override val spec: TileSpec,
        val position: Int = POSITION_AT_END,
    ) : AutoAddSignal

    /** Signal for removing a tile. */
    data class Remove(
        override val spec: TileSpec,
    ) : AutoAddSignal
}
