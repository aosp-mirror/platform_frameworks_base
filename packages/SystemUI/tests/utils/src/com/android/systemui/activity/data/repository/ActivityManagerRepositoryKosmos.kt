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

package com.android.systemui.activity.data.repository

import android.app.activityManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.log.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow

val Kosmos.activityManagerRepository by Kosmos.Fixture { FakeActivityManagerRepository() }

val Kosmos.realActivityManagerRepository by
    Kosmos.Fixture { ActivityManagerRepositoryImpl(testDispatcher, activityManager) }

class FakeActivityManagerRepository : ActivityManagerRepository {
    private val uidFlows = mutableMapOf<Int, MutableList<MutableStateFlow<Boolean>>>()

    var startingIsAppVisibleValue = false

    override fun createIsAppVisibleFlow(
        creationUid: Int,
        logger: Logger,
        identifyingLogTag: String,
    ): MutableStateFlow<Boolean> {
        val newFlow = MutableStateFlow(startingIsAppVisibleValue)
        uidFlows.computeIfAbsent(creationUid) { mutableListOf() }.add(newFlow)
        return newFlow
    }

    fun setIsAppVisible(uid: Int, isAppVisible: Boolean) {
        uidFlows[uid]?.forEach { stateFlow -> stateFlow.value = isAppVisible }
    }
}

val ActivityManagerRepository.fake
    get() = this as FakeActivityManagerRepository
