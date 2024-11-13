/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.util.settings

import android.annotation.UserIdInt
import android.database.ContentObserver
import com.android.systemui.Flags
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Kotlin extension functions for [SettingsProxy]. */
object SettingsProxyExt {

    /** Returns a flow of [Unit] that is invoked each time that content is updated. */
    fun UserSettingsProxy.observerFlow(
        @UserIdInt userId: Int,
        vararg names: String,
    ): Flow<Unit> {
        return conflatedCallbackFlow {
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }

            names.forEach { name ->
                if (Flags.settingsExtRegisterContentObserverOnBgThread()) {
                    registerContentObserverForUser(name, observer, userId)
                } else {
                    registerContentObserverForUserSync(name, observer, userId)
                }
            }

            awaitClose {
                if (Flags.settingsExtRegisterContentObserverOnBgThread()) {
                    unregisterContentObserverAsync(observer)
                } else {
                    unregisterContentObserverSync(observer)
                }
            }
        }
    }

    /** Returns a flow of [Unit] that is invoked each time that content is updated. */
    fun SettingsProxy.observerFlow(
        vararg names: String,
    ): Flow<Unit> {
        return conflatedCallbackFlow {
            val observer =
                object : ContentObserver(null) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }

            names.forEach { name ->
                if (Flags.settingsExtRegisterContentObserverOnBgThread()) {
                    registerContentObserver(name, observer)
                } else {
                    registerContentObserverSync(name, observer)
                }
            }

            awaitClose {
                if (Flags.settingsExtRegisterContentObserverOnBgThread()) {
                    unregisterContentObserverAsync(observer)
                } else {
                    unregisterContentObserverSync(observer)
                }
            }
        }
    }
}
