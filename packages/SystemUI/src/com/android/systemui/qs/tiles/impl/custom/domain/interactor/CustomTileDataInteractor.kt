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

package com.android.systemui.qs.tiles.impl.custom.domain.interactor

import android.os.UserHandle
import android.service.quicksettings.Tile
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.impl.custom.data.entity.CustomTileDefaults
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTileDefaultsRepository
import com.android.systemui.qs.tiles.impl.custom.data.repository.CustomTilePackageUpdatesRepository
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import com.android.systemui.user.data.repository.UserRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

@QSTileScope
@OptIn(ExperimentalCoroutinesApi::class)
class CustomTileDataInteractor
@Inject
constructor(
    private val tileSpec: TileSpec.CustomTileSpec,
    private val defaultsRepository: CustomTileDefaultsRepository,
    private val serviceInteractor: CustomTileServiceInteractor,
    private val customTileInteractor: CustomTileInteractor,
    private val packageUpdatesRepository: CustomTilePackageUpdatesRepository,
    userRepository: UserRepository,
    @QSTileScope private val tileScope: CoroutineScope,
) : QSTileDataInteractor<CustomTileDataModel> {

    private val mutableUserFlow = MutableStateFlow(userRepository.getSelectedUserInfo().userHandle)
    private val bindingFlow =
        mutableUserFlow
            .flatMapLatest { user ->
                ConflatedCallbackFlow.conflatedCallbackFlow {
                    serviceInteractor.setUser(user)

                    // Wait for the CustomTileInteractor to become initialized first, because
                    // binding
                    // the service might access it
                    customTileInteractor.initForUser(user)
                    // Bind the TileService for not active tile
                    serviceInteractor.bindOnStart()

                    packageUpdatesRepository
                        .getPackageChangesForUser(user)
                        .onEach {
                            defaultsRepository.requestNewDefaults(
                                user,
                                tileSpec.componentName,
                                true
                            )
                        }
                        .launchIn(this)

                    send(Unit)
                    awaitClose { serviceInteractor.unbind() }
                }
            }
            .shareIn(tileScope, SharingStarted.WhileSubscribed())

    init {
        // Initialize binding once to flush all the pending messages inside
        // CustomTileServiceInteractor and then unbind if the tile data isn't observed. This ensures
        // that all the interactors are loaded and warmed up before binding.
        tileScope.launch { bindingFlow.first() }
    }

    override fun tileData(
        user: UserHandle,
        triggers: Flow<DataUpdateTrigger>
    ): Flow<CustomTileDataModel> {
        tileScope.launch { mutableUserFlow.emit(user) }
        return bindingFlow.combine(triggers) { _, _ -> }.flatMapLatest { dataFlow(user) }
    }

    private fun dataFlow(user: UserHandle): Flow<CustomTileDataModel> =
        combine(
            serviceInteractor.refreshEvents.onStart { emit(Unit) },
            serviceInteractor.callingAppIds,
            customTileInteractor.getTiles(user),
            defaultsRepository.defaults(user).mapNotNull { it as? CustomTileDefaults.Result },
        ) { _: Unit, callingAppId: Int, tile: Tile, defaults: CustomTileDefaults.Result ->
            CustomTileDataModel(
                user = user,
                componentName = tileSpec.componentName,
                tile = tile,
                callingAppUid = callingAppId,
                hasPendingBind = serviceInteractor.hasPendingBind(),
                defaultTileLabel = defaults.label,
                defaultTileIcon = defaults.icon,
                isToggleable = customTileInteractor.isTileToggleable(),
            )
        }

    override fun availability(user: UserHandle): Flow<Boolean> =
        with(defaultsRepository) {
            requestNewDefaults(user, tileSpec.componentName)
            return defaults(user).map { it is CustomTileDefaults.Result }
        }
}
