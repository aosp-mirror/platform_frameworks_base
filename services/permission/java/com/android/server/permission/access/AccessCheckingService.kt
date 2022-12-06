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
        getState {
            with(policy) { getDecision(subject, `object`) }
        }

    fun setDecision(subject: AccessUri, `object`: AccessUri, decision: Int) {
        mutateState {
            with(policy) { setDecision(subject, `object`, decision) }
        }
    }

    fun onUserAdded(userId: Int) {
        mutateState {
            with(policy) { onUserAdded(userId) }
        }
    }

    fun onUserRemoved(userId: Int) {
        mutateState {
            with(policy) { onUserRemoved(userId) }
        }
    }

    fun onPackageAdded(packageState: PackageState) {
        mutateState {
            with(policy) { onPackageAdded(packageState) }
        }
    }

    fun onPackageRemoved(packageState: PackageState) {
        mutateState {
            with(policy) { onPackageRemoved(packageState) }
        }
    }

    internal inline fun <T> getState(action: GetStateScope.() -> T): T =
        GetStateScope(state).action()

    internal inline fun mutateState(action: MutateStateScope.() -> Unit) {
        synchronized(stateLock) {
            val oldState = state
            val newState = oldState.copy()
            MutateStateScope(oldState, newState).action()
            persistence.write(newState)
            state = newState
        }
    }

    internal fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        policy.getSchemePolicy(subjectScheme, objectScheme)
}
