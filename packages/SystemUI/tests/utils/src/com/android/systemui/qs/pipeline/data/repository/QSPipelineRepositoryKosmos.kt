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

package com.android.systemui.qs.pipeline.data.repository

import com.android.systemui.kosmos.Kosmos

/** This fake uses 0 as the minimum number of tiles. That means that no tiles is a valid state. */
var Kosmos.fakeMinimumTilesRepository by Kosmos.Fixture { MinimumTilesFixedRepository(0) }
val Kosmos.minimumTilesRepository: MinimumTilesRepository by
    Kosmos.Fixture { fakeMinimumTilesRepository }

var Kosmos.fakeDefaultTilesRepository by Kosmos.Fixture { FakeDefaultTilesRepository() }
val Kosmos.defaultTilesRepository: DefaultTilesRepository by
    Kosmos.Fixture { fakeDefaultTilesRepository }

val Kosmos.fakeTileSpecRepository by
    Kosmos.Fixture { FakeTileSpecRepository(defaultTilesRepository) }
var Kosmos.tileSpecRepository: TileSpecRepository by Kosmos.Fixture { fakeTileSpecRepository }

val Kosmos.fakeAutoAddRepository by Kosmos.Fixture { FakeAutoAddRepository() }
var Kosmos.autoAddRepository: AutoAddRepository by Kosmos.Fixture { fakeAutoAddRepository }

val Kosmos.fakeRestoreRepository by Kosmos.Fixture { FakeQSSettingsRestoredRepository() }
var Kosmos.restoreRepository: QSSettingsRestoredRepository by
    Kosmos.Fixture { fakeRestoreRepository }

val Kosmos.fakeInstalledTilesRepository by
    Kosmos.Fixture { FakeInstalledTilesComponentRepository() }
var Kosmos.installedTilesRepository: InstalledTilesComponentRepository by
    Kosmos.Fixture { fakeInstalledTilesRepository }

val Kosmos.fakeCustomTileAddedRepository by Kosmos.Fixture { FakeCustomTileAddedRepository() }
var Kosmos.customTileAddedRepository: CustomTileAddedRepository by
    Kosmos.Fixture { fakeCustomTileAddedRepository }
