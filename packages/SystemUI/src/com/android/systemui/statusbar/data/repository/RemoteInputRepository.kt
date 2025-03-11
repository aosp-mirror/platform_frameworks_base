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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.RemoteInputController
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

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

    /** Close any active remote inputs */
    fun closeRemoteInputs()
}

@SysUISingleton
class RemoteInputRepositoryImpl
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    private val notificationRemoteInputManager: NotificationRemoteInputManager,
) : RemoteInputRepository {
    private val _isRemoteInputActive = conflatedCallbackFlow {
        val callback =
            object : RemoteInputController.Callback {
                override fun onRemoteInputActive(active: Boolean) {
                    trySend(active)
                }
            }
        trySend(notificationRemoteInputManager.isRemoteInputActive)
        notificationRemoteInputManager.addControllerCallback(callback)
        awaitClose { notificationRemoteInputManager.removeControllerCallback(callback) }
    }

    override val isRemoteInputActive =
        if (SceneContainerFlag.isEnabled) {
            _isRemoteInputActive.stateIn(
                applicationScope,
                SharingStarted.WhileSubscribed(),
                notificationRemoteInputManager.isRemoteInputActive,
            )
        } else {
            _isRemoteInputActive
        }

    override val remoteInputRowBottomBound = MutableStateFlow<Float?>(null)

    override fun setRemoteInputRowBottomBound(bottom: Float?) {
        remoteInputRowBottomBound.value = bottom
    }

    override fun closeRemoteInputs() {
        notificationRemoteInputManager.closeRemoteInputs()
    }
}

@Module
interface RemoteInputRepositoryModule {
    @Binds fun bindImpl(impl: RemoteInputRepositoryImpl): RemoteInputRepository
}
