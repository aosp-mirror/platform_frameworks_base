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

package com.android.systemui.lowlightclock

import android.content.Intent
import android.content.IntentFilter
import android.os.UserManager
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shared.condition.Condition
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class DirectBootCondition
@Inject
constructor(
    broadcastDispatcher: BroadcastDispatcher,
    private val userManager: UserManager,
    @Application private val coroutineScope: CoroutineScope,
) : Condition(coroutineScope) {
    private var job: Job? = null
    private val directBootFlow =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(Intent.ACTION_USER_UNLOCKED))
            .map { !userManager.isUserUnlocked }
            .cancellable()
            .distinctUntilChanged()

    override fun start() {
        job = coroutineScope.launch { directBootFlow.collect { updateCondition(it) } }
        updateCondition(!userManager.isUserUnlocked)
    }

    override fun stop() {
        job?.cancel()
    }

    override fun getStartStrategy(): Int {
        return START_EAGERLY
    }
}
