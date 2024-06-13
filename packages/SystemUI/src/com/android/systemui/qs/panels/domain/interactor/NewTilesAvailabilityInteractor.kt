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

package com.android.systemui.qs.panels.domain.interactor

import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.QSTileAvailabilityInteractor
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.utils.coroutines.flow.flatMapLatestConflated
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Uses the [QSTileAvailabilityInteractor] from the new tiles to provide a map of availability, for
 * the current user.
 *
 * This map contains every platform tile that can be constructed in the new tile infrastructure.
 */
@SysUISingleton
class NewTilesAvailabilityInteractor
@Inject
constructor(
        private val availabilityInteractors:
                Map<String, @JvmSuppressWildcards QSTileAvailabilityInteractor>,
        userRepository: UserRepository,
) {
    val newTilesAvailable: Flow<Map<TileSpec, Boolean>> =
            userRepository.selectedUserInfo.map { it.id }
                    .flatMapLatestConflated { userId ->
                        if (availabilityInteractors.isEmpty()) {
                            flowOf(emptyMap())
                        } else {
                            combine(availabilityInteractors.map { (spec, interactor) ->
                                interactor.availability(UserHandle.of(userId)).map {
                                    TileSpec.create(spec) to it
                                }
                            }) {
                                it.toMap()
                            }
                        }
                    }
}
