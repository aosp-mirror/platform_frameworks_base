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

import com.android.internal.logging.InstanceId
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.model.TileCategory

object QSTileConfigTestBuilder {

    fun build(configure: BuildingScope.() -> Unit = {}): QSTileConfig =
        BuildingScope().apply(configure).build()

    class BuildingScope {
        var tileSpec: TileSpec = TileSpec.create("test_spec")
        var uiConfig: QSTileUIConfig = QSTileUIConfig.Empty
        var instanceId: InstanceId = InstanceId.fakeInstanceId(0)
        var metricsSpec: String = tileSpec.spec
        var policy: QSTilePolicy = QSTilePolicy.NoRestrictions
        var category: TileCategory = TileCategory.UNKNOWN

        fun build() =
            QSTileConfig(
                tileSpec,
                uiConfig,
                instanceId,
                category,
                metricsSpec,
                policy,
            )
    }
}
