/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.statusbar.notification.data.repository

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.android.settingslib.flags.Flags
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Provides state of volume policy and restrictions imposed by notifications. */
interface ZenModeRepository {
    /** @see NotificationManager.getConsolidatedNotificationPolicy */
    val consolidatedNotificationPolicy: StateFlow<NotificationManager.Policy?>

    /** @see NotificationManager.getZenMode */
    val globalZenMode: StateFlow<Int?>
}

class ZenModeRepositoryImpl(
    private val context: Context,
    private val notificationManager: NotificationManager,
    val scope: CoroutineScope,
    val backgroundCoroutineContext: CoroutineContext,
) : ZenModeRepository {

    private val notificationBroadcasts =
        callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            intent?.action?.let { action -> launch { send(action) } }
                        }
                    }

                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                        addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
                        if (Flags.volumePanelBroadcastFix() && android.app.Flags.modesApi())
                            addAction(
                                NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
                    })

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .apply {
                if (Flags.volumePanelBroadcastFix()) {
                    flowOn(backgroundCoroutineContext)
                    stateIn(scope, SharingStarted.WhileSubscribed(), null)
                } else {
                    shareIn(
                        started = SharingStarted.WhileSubscribed(),
                        scope = scope,
                    )
                }
            }

    override val consolidatedNotificationPolicy: StateFlow<NotificationManager.Policy?> =
        if (Flags.volumePanelBroadcastFix() && android.app.Flags.modesApi())
            flowFromBroadcast(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED) {
                notificationManager.consolidatedNotificationPolicy
            }
        else
            flowFromBroadcast(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED) {
                notificationManager.consolidatedNotificationPolicy
            }

    override val globalZenMode: StateFlow<Int?> =
        flowFromBroadcast(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
            notificationManager.zenMode
        }

    private fun <T> flowFromBroadcast(intentAction: String, mapper: () -> T) =
        notificationBroadcasts
            .filter { intentAction == it }
            .map { mapper() }
            .onStart { emit(mapper()) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)
}
