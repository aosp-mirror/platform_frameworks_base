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

package com.android.systemui.qs.tiles.base.interactor

import javax.annotation.CheckReturnValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest

class FakeQSTileDataInteractor<T>(
    private val dataFlow: MutableSharedFlow<T> = MutableSharedFlow(replay = Int.MAX_VALUE),
    private val availabilityFlow: MutableSharedFlow<Boolean> =
        MutableSharedFlow(replay = Int.MAX_VALUE),
) : QSTileDataInteractor<T> {

    private val mutableDataRequests = mutableListOf<DataRequest>()
    val dataRequests: List<DataRequest> = mutableDataRequests

    private val mutableAvailabilityRequests = mutableListOf<AvailabilityRequest>()
    val availabilityRequests: List<AvailabilityRequest> = mutableAvailabilityRequests

    @CheckReturnValue fun emitData(data: T): Boolean = dataFlow.tryEmit(data)

    fun tryEmitAvailability(isAvailable: Boolean): Boolean = availabilityFlow.tryEmit(isAvailable)
    suspend fun emitAvailability(isAvailable: Boolean) = availabilityFlow.emit(isAvailable)

    override fun tileData(userId: Int, triggers: Flow<DataUpdateTrigger>): Flow<T> {
        mutableDataRequests.add(DataRequest(userId))
        return triggers.flatMapLatest { dataFlow }
    }

    override fun availability(userId: Int): Flow<Boolean> {
        mutableAvailabilityRequests.add(AvailabilityRequest(userId))
        return availabilityFlow
    }

    data class DataRequest(val userId: Int)
    data class AvailabilityRequest(val userId: Int)
}
