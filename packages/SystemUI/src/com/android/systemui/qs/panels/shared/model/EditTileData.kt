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

package com.android.systemui.qs.panels.shared.model

import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory

data class EditTileData(
    val tileSpec: TileSpec,
    val icon: Icon,
    val label: Text,
    val appName: Text?,
    val category: TileCategory,
) {
    init {
        check(
            (tileSpec is TileSpec.PlatformTileSpec && appName == null) ||
                (tileSpec is TileSpec.CustomTileSpec && appName != null)
        ) {
            "tileSpec: $tileSpec - appName: $appName. " +
                "appName must be non-null for custom tiles and only for custom tiles."
        }
    }
}
