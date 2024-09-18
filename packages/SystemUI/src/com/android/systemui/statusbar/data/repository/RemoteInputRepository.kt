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

package com.android.systemui.statusbar.data.repository

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.RemoteInputController
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Repository used for tracking the state of notification remote input (e.g. when the user presses
 * "reply" on a notification and the keyboard opens).
 */
interface RemoteInputRepository {
    /** Whether remote input is currently active for any notification. */
    val isRemoteInputActive: Flow<Boolean>

    /**
     * The bottom bound of the currently focused remote input notification row, or null if there
     * isn't one.
     */
    val remoteInputRowBottomBound: Flow<Float?>

    fun setRemoteInputRowBottomBound(bottom: Float?)
}

@SysUISingleton
class RemoteInputRepositoryImpl
@Inject
constructor(private val notificationRemoteInputManager: NotificationRemoteInputManager) :
    RemoteInputRepository {
    override val isRemoteInputActive: Flow<Boolean> = conflatedCallbackFlow {
        trySend(false) // initial value is false
        val callback =
            object : RemoteInputController.Callback {
                override fun onRemoteInputActive(active: Boolean) {
                    trySend(active)
                }
            }
        notificationRemoteInputManager.addControllerCallback(callback)
        awaitClose { notificationRemoteInputManager.removeControllerCallback(callback) }
    }

    override val remoteInputRowBottomBound = MutableStateFlow<Float?>(null)

    override fun setRemoteInputRowBottomBound(bottom: Float?) {
        remoteInputRowBottomBound.value = bottom
    }
}

@Module
interface RemoteInputRepositoryModule {
    @Binds fun bindImpl(impl: RemoteInputRepositoryImpl): RemoteInputRepository
}
