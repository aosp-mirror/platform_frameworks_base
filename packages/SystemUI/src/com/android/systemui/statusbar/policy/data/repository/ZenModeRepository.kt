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

package com.android.systemui.statusbar.policy.data.repository

import android.app.NotificationManager
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.statusbar.policy.ZenModeController
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/**
 * A repository that holds information about the status and configuration of Zen Mode (or Do Not
 * Disturb/DND Mode).
 */
interface ZenModeRepository {
    val zenMode: Flow<Int>
    val consolidatedNotificationPolicy: Flow<NotificationManager.Policy?>
}

class ZenModeRepositoryImpl
@Inject
constructor(
    private val zenModeController: ZenModeController,
) : ZenModeRepository {
    // TODO(b/308591859): ZenModeController should use flows instead of callbacks. The
    // conflatedCallbackFlows here should be replaced eventually, see:
    // https://docs.google.com/document/d/1gAiuYupwUAFdbxkDXa29A4aFNu7XoCd7sCIk31WTnHU/edit?resourcekey=0-J4ZBiUhLhhQnNobAcI2vIw

    override val zenMode: Flow<Int> = conflatedCallbackFlow {
        val callback =
            object : ZenModeController.Callback {
                override fun onZenChanged(zen: Int) {
                    trySend(zen)
                }
            }
        zenModeController.addCallback(callback)
        trySend(zenModeController.zen)

        awaitClose { zenModeController.removeCallback(callback) }
    }

    override val consolidatedNotificationPolicy: Flow<NotificationManager.Policy?> =
        conflatedCallbackFlow {
            val callback =
                object : ZenModeController.Callback {
                    override fun onConsolidatedPolicyChanged(policy: NotificationManager.Policy?) {
                        trySend(policy)
                    }
                }
            zenModeController.addCallback(callback)
            trySend(zenModeController.consolidatedPolicy)

            awaitClose { zenModeController.removeCallback(callback) }
        }
}

@Module
interface ZenModeRepositoryModule {
    @Binds fun bindImpl(impl: ZenModeRepositoryImpl): ZenModeRepository
}
