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

package com.android.settingslib.notification.data.repository

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.NotificationManager.EXTRA_NOTIFICATION_POLICY
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import com.android.settingslib.flags.Flags
import com.android.settingslib.notification.modes.ZenMode
import com.android.settingslib.notification.modes.ZenModesBackend
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
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

    /** A list of all existing priority modes. */
    val modes: Flow<List<ZenMode>>

    fun getModes(): List<ZenMode>

    fun activateMode(zenMode: ZenMode, duration: Duration? = null)

    fun deactivateMode(zenMode: ZenMode)
}

@SuppressLint("SharedFlowCreation")
class ZenModeRepositoryImpl(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val backend: ZenModesBackend,
    private val contentResolver: ContentResolver,
    val scope: CoroutineScope,
    val backgroundCoroutineContext: CoroutineContext,
    // This is nullable just to simplify testing, since SettingsLib doesn't have a good way
    // to create a fake handler.
    val backgroundHandler: Handler?,
) : ZenModeRepository {

    private val notificationBroadcasts by lazy {
        callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            intent?.let { launch { send(it) } }
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
                    },
                    /* broadcastPermission = */ null,
                    /* scheduler = */ if (Flags.volumePanelBroadcastFix()) {
                        backgroundHandler
                    } else {
                        null
                    },
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .let {
                if (Flags.volumePanelBroadcastFix()) {
                    // Share the flow to avoid having multiple broadcasts.
                    it.flowOn(backgroundCoroutineContext)
                        .shareIn(started = SharingStarted.WhileSubscribed(), scope = scope)
                } else {
                    it.shareIn(started = SharingStarted.WhileSubscribed(), scope = scope)
                }
            }
    }

    override val consolidatedNotificationPolicy: StateFlow<NotificationManager.Policy?> by lazy {
        if (Flags.volumePanelBroadcastFix() && android.app.Flags.modesApi())
            flowFromBroadcast(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED) {
                // If available, get the value from extras to avoid a potential binder call.
                it?.extras?.getParcelable(EXTRA_NOTIFICATION_POLICY)
                    ?: notificationManager.consolidatedNotificationPolicy
            }
        else
            flowFromBroadcast(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED) {
                notificationManager.consolidatedNotificationPolicy
            }
    }

    override val globalZenMode: StateFlow<Int?> by lazy {
        flowFromBroadcast(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
            notificationManager.zenMode
        }
    }

    private fun <T> flowFromBroadcast(intentAction: String, mapper: (Intent?) -> T) =
        notificationBroadcasts
            .filter { intentAction == it.action }
            .map { mapper(it) }
            .onStart { emit(mapper(null)) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private val zenConfigChanged by lazy {
        if (android.app.Flags.modesUi()) {
            callbackFlow {
                    // emit an initial value
                    trySend(Unit)

                    val observer =
                        object : ContentObserver(backgroundHandler) {
                            override fun onChange(selfChange: Boolean) {
                                trySend(Unit)
                            }
                        }

                    contentResolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ZEN_MODE),
                        /* notifyForDescendants= */ false,
                        observer)
                    contentResolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ZEN_MODE_CONFIG_ETAG),
                        /* notifyForDescendants= */ false,
                        observer)

                    awaitClose { contentResolver.unregisterContentObserver(observer) }
                }
                .flowOn(backgroundCoroutineContext)
        } else {
            flowOf(Unit)
        }
    }

    override val modes: Flow<List<ZenMode>> by lazy {
        if (android.app.Flags.modesUi()) {
            zenConfigChanged
                .map { backend.modes }
                .distinctUntilChanged()
                .flowOn(backgroundCoroutineContext)
        } else {
            flowOf(emptyList())
        }
    }

    /**
     * Gets the current list of [ZenMode] instances according to the backend.
     *
     * This is necessary, and cannot be supplanted by making [modes] a StateFlow, because it will be
     * called whenever we know or suspect that [modes] may not have caught up to the latest data
     * (such as right after a user switch).
     */
    override fun getModes(): List<ZenMode> = backend.modes

    override fun activateMode(zenMode: ZenMode, duration: Duration?) {
        backend.activateMode(zenMode, duration)
    }

    override fun deactivateMode(zenMode: ZenMode) {
        backend.deactivateMode(zenMode)
    }
}
