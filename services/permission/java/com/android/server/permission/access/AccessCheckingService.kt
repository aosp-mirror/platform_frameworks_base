/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access

import com.android.internal.annotations.Keep
import com.android.server.permission.access.external.PackageState

@Keep
class AccessCheckingService {
    @Volatile
    private lateinit var state: AccessState
    private val stateLock = Any()

    private val policy = AccessPolicy()

    private val persistence = AccessPersistence(policy)

    fun init() {
        val state = AccessState()
        state.systemState.userIds.apply {
            // TODO: Get and add all user IDs.
            // TODO: Maybe get and add all packages?
        }
        persistence.read(state)
        this.state = state
    }

    fun getDecision(subject: AccessUri, `object`: AccessUri): Int =
        policy.getDecision(subject, `object`, state)

    fun setDecision(subject: AccessUri, `object`: AccessUri, decision: Int) {
        mutateState { oldState, newState ->
            policy.setDecision(subject, `object`, decision, oldState, newState)
        }
    }

    fun onUserAdded(userId: Int) {
        mutateState { oldState, newState ->
            policy.onUserAdded(userId, oldState, newState)
        }
    }

    fun onUserRemoved(userId: Int) {
        mutateState { oldState, newState ->
            policy.onUserRemoved(userId, oldState, newState)
        }
    }

    fun onPackageAdded(packageState: PackageState) {
        mutateState { oldState, newState ->
            policy.onPackageAdded(packageState, oldState, newState)
        }
    }

    fun onPackageRemoved(packageState: PackageState) {
        mutateState { oldState, newState ->
            policy.onPackageRemoved(packageState, oldState, newState)
        }
    }

    // TODO: Replace (oldState, newState) with Kotlin context receiver once it's stabilized.
    private inline fun mutateState(action: (oldState: AccessState, newState: AccessState) -> Unit) {
        synchronized(stateLock) {
            val oldState = state
            val newState = oldState.copy()
            action(oldState, newState)
            persistence.write(newState)
            state = newState
        }
    }
}
