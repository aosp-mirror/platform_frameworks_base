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

import android.os.UserHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest

class FakeQSTileDataInteractor<T> : QSTileDataInteractor<T> {

    private val dataFlow: MutableSharedFlow<T> = MutableSharedFlow(replay = 1)
    val dataSubscriptionCount
        get() = dataFlow.subscriptionCount
    private val availabilityFlow: MutableSharedFlow<Boolean> = MutableSharedFlow(replay = 1)
    val availabilitySubscriptionCount
        get() = availabilityFlow.subscriptionCount

    private val mutableTriggers = mutableListOf<DataUpdateTrigger>()
    val triggers: List<DataUpdateTrigger> = mutableTriggers

    private val mutableDataRequests = mutableListOf<DataRequest>()
    val dataRequests: List<DataRequest> = mutableDataRequests

    private val mutableAvailabilityRequests = mutableListOf<AvailabilityRequest>()
    val availabilityRequests: List<AvailabilityRequest> = mutableAvailabilityRequests

    suspend fun emitData(data: T): Unit = dataFlow.emit(data)

    fun tryEmitAvailability(isAvailable: Boolean): Boolean = availabilityFlow.tryEmit(isAvailable)
    suspend fun emitAvailability(isAvailable: Boolean) = availabilityFlow.emit(isAvailable)

    override fun tileData(user: UserHandle, triggers: Flow<DataUpdateTrigger>): Flow<T> {
        mutableDataRequests.add(DataRequest(user))
        return triggers.flatMapLatest {
            mutableTriggers.add(it)
            dataFlow
        }
    }

    override fun availability(user: UserHandle): Flow<Boolean> {
        mutableAvailabilityRequests.add(AvailabilityRequest(user))
        return availabilityFlow
    }

    data class DataRequest(val user: UserHandle)
    data class AvailabilityRequest(val user: UserHandle)
}
