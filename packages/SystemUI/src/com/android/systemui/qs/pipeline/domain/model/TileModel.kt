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

import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.pipeline.shared.TileSpec

/**
 * Container for a [tile] and its [spec]. The following must be true:
 * ```
 * spec.spec == tile.tileSpec
 * ```
 */
data class TileModel(val spec: TileSpec, val tile: QSTile) {
    init {
        check(spec.spec == tile.tileSpec)
    }
}
