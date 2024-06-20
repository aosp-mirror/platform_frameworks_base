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

package com.android.systemui.qs.pipeline.domain.interactor

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.qs.external.customTileStatePersister
import com.android.systemui.qs.external.tileLifecycleManagerFactory
import com.android.systemui.qs.pipeline.data.repository.customTileAddedRepository
import com.android.systemui.qs.pipeline.data.repository.installedTilesRepository
import com.android.systemui.qs.pipeline.data.repository.minimumTilesRepository
import com.android.systemui.qs.pipeline.data.repository.retailModeRepository
import com.android.systemui.qs.pipeline.data.repository.tileSpecRepository
import com.android.systemui.qs.pipeline.shared.logging.qsLogger
import com.android.systemui.qs.pipeline.shared.pipelineFlagsRepository
import com.android.systemui.qs.qsTileFactory
import com.android.systemui.qs.tiles.di.newQSTileFactory
import com.android.systemui.settings.userTracker
import com.android.systemui.user.data.repository.userRepository

val Kosmos.currentTilesInteractor: CurrentTilesInteractor by
    Kosmos.Fixture {
        CurrentTilesInteractorImpl(
            tileSpecRepository,
            installedTilesRepository,
            userRepository,
            minimumTilesRepository,
            retailModeRepository,
            customTileStatePersister,
            { newQSTileFactory },
            qsTileFactory,
            customTileAddedRepository,
            tileLifecycleManagerFactory,
            userTracker,
            testDispatcher,
            testDispatcher,
            applicationCoroutineScope,
            qsLogger,
            pipelineFlagsRepository,
        )
    }
