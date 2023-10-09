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
package com.android.systemui.statusbar.notification.data.repository

import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** View-states pertaining to notifications on the keyguard. */
interface NotificationsKeyguardViewStateRepository {
    /** Are notifications fully hidden from view? */
    val areNotificationsFullyHidden: Flow<Boolean>

    /** Is a pulse expansion occurring? */
    val isPulseExpanding: Flow<Boolean>
}

@Module
interface NotificationsKeyguardStateRepositoryModule {
    @Binds
    fun bindImpl(
        impl: NotificationsKeyguardViewStateRepositoryImpl
    ): NotificationsKeyguardViewStateRepository
}

@SysUISingleton
class NotificationsKeyguardViewStateRepositoryImpl
@Inject
constructor(
    wakeUpCoordinator: NotificationWakeUpCoordinator,
) : NotificationsKeyguardViewStateRepository {
    override val areNotificationsFullyHidden: Flow<Boolean> = conflatedCallbackFlow {
        val listener =
            object : NotificationWakeUpCoordinator.WakeUpListener {
                override fun onFullyHiddenChanged(isFullyHidden: Boolean) {
                    trySend(isFullyHidden)
                }
            }
        trySend(wakeUpCoordinator.notificationsFullyHidden)
        wakeUpCoordinator.addListener(listener)
        awaitClose { wakeUpCoordinator.removeListener(listener) }
    }

    override val isPulseExpanding: Flow<Boolean> = conflatedCallbackFlow {
        val listener =
            object : NotificationWakeUpCoordinator.WakeUpListener {
                override fun onPulseExpansionChanged(expandingChanged: Boolean) {
                    trySend(expandingChanged)
                }
            }
        trySend(wakeUpCoordinator.isPulseExpanding())
        wakeUpCoordinator.addListener(listener)
        awaitClose { wakeUpCoordinator.removeListener(listener) }
    }
}
