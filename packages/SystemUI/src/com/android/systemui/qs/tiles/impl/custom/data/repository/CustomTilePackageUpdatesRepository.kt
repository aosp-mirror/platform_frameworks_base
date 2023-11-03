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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.qs.external.TileServiceManager
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.custom.di.bound.CustomTileBoundScope
import com.android.systemui.qs.tiles.impl.custom.di.bound.CustomTileUser
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn

interface CustomTilePackageUpdatesRepository {

    val packageChanges: Flow<Unit>
}

@CustomTileBoundScope
class CustomTilePackageUpdatesRepositoryImpl
@Inject
constructor(
    tileSpec: TileSpec.CustomTileSpec,
    @CustomTileUser user: UserHandle,
    serviceManager: TileServiceManager,
    defaultsRepository: CustomTileDefaultsRepository,
    @CustomTileBoundScope boundScope: CoroutineScope,
) : CustomTilePackageUpdatesRepository {

    override val packageChanges: Flow<Unit> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                serviceManager.setTileChangeListener { changedComponentName ->
                    if (changedComponentName == tileSpec.componentName) {
                        trySend(Unit)
                    }
                }

                awaitClose { serviceManager.setTileChangeListener(null) }
            }
            .onEach { defaultsRepository.requestNewDefaults(user, tileSpec.componentName, true) }
            .shareIn(boundScope, SharingStarted.WhileSubscribed())
}
