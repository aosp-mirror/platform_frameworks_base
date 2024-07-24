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
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.qs.external.FakeCustomTileStatePersister
import com.android.systemui.qs.external.tileServices
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.actions.FakeQSTileIntentUserInputHandler
import com.android.systemui.qs.tiles.base.logging.QSTileLogger
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTilePackageUpdatesRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakeCustomTileRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.FakePackageManagerAdapterFacade
import com.android.systemui.qs.tiles.impl.custom.domain.interactor.CustomTileInteractor
import com.android.systemui.qs.tiles.impl.custom.domain.interactor.CustomTileServiceInteractor
import com.android.systemui.qs.tiles.impl.custom.domain.interactor.CustomTileUserActionInteractor
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig
import com.android.systemui.qs.tiles.viewmodel.QSTileConfigTestBuilder
import com.android.systemui.user.data.repository.userRepository
import com.android.systemui.util.mockito.mock

var Kosmos.tileSpec: TileSpec.CustomTileSpec by Kosmos.Fixture()

var Kosmos.customTileQsTileConfig: QSTileConfig by
    Kosmos.Fixture { QSTileConfigTestBuilder.build { tileSpec = this@Fixture.tileSpec } }
val Kosmos.qsTileLogger: QSTileLogger by Kosmos.Fixture { mock {} }

val Kosmos.customTileStatePersister: FakeCustomTileStatePersister by
    Kosmos.Fixture { FakeCustomTileStatePersister() }

val Kosmos.customTileInteractor: CustomTileInteractor by
    Kosmos.Fixture {
        CustomTileInteractor(
            tileSpec,
            customTileDefaultsRepository,
            customTileRepository,
            testScope.backgroundScope,
            testScope.testScheduler,
        )
    }

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

val Kosmos.customTileServiceInteractor: CustomTileServiceInteractor by
    Kosmos.Fixture {
        CustomTileServiceInteractor(
            tileSpec,
            activityStarter,
            { customTileUserActionInteractor },
            customTileInteractor,
            userRepository,
            qsTileLogger,
            tileServices,
            testScope.backgroundScope,
        )
    }

val Kosmos.customTileUserActionInteractor: CustomTileUserActionInteractor by
    Kosmos.Fixture {
        CustomTileUserActionInteractor(
            testCase.context,
            tileSpec,
            qsTileLogger,
            mock {},
            mock {},
            FakeQSTileIntentUserInputHandler(),
            testDispatcher,
            customTileServiceInteractor,
        )
    }
