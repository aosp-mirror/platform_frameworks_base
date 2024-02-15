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

package com.android.systemui.qs.tiles.impl.custom

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.external.FakeCustomTileStatePersister
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTilePackageUpdatesRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakePackageManagerAdapterFacade

var Kosmos.tileSpec: TileSpec.CustomTileSpec by Kosmos.Fixture()

val Kosmos.customTileStatePersister: FakeCustomTileStatePersister by
    Kosmos.Fixture { FakeCustomTileStatePersister() }

val Kosmos.customTileRepository: FakeCustomTileRepository by
    Kosmos.Fixture {
        FakeCustomTileRepository(
            tileSpec,
            customTileStatePersister,
            packageManagerAdapterFacade,
            testScope.testScheduler,
        )
    }

val Kosmos.customTileDefaultsRepository: FakeCustomTileDefaultsRepository by
    Kosmos.Fixture { FakeCustomTileDefaultsRepository() }

val Kosmos.customTilePackagesUpdatesRepository: FakeCustomTilePackageUpdatesRepository by
    Kosmos.Fixture { FakeCustomTilePackageUpdatesRepository() }

val Kosmos.packageManagerAdapterFacade: FakePackageManagerAdapterFacade by
    Kosmos.Fixture { FakePackageManagerAdapterFacade(tileSpec.componentName) }
