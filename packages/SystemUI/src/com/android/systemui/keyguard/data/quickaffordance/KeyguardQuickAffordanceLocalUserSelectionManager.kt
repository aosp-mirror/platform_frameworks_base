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

package com.android.systemui.keyguard.data.quickaffordance

import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import com.android.systemui.backup.BackupHelper
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart

/**
 * Manages and provides access to the current "selections" of keyguard quick affordances, answering
 * the question "which affordances should the keyguard show?" for the user associated with the
 * System UI process.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardQuickAffordanceLocalUserSelectionManager
@Inject
constructor(
    @Application private val context: Context,
    private val userFileManager: UserFileManager,
    private val userTracker: UserTracker,
    broadcastDispatcher: BroadcastDispatcher,
) : KeyguardQuickAffordanceSelectionManager {

    private var sharedPrefs: SharedPreferences = instantiateSharedPrefs()

    private val userId: Flow<Int> = conflatedCallbackFlow {
        val callback =
            object : UserTracker.Callback {
                override fun onUserChanged(newUser: Int, userContext: Context) {
                    trySendWithFailureLogging(newUser, TAG)
                }
            }

        userTracker.addCallback(callback) { it.run() }
        trySendWithFailureLogging(userTracker.userId, TAG)

        awaitClose { userTracker.removeCallback(callback) }
    }

    private val defaults: Map<String, List<String>> by lazy {
        context.resources
            .getStringArray(R.array.config_keyguardQuickAffordanceDefaults)
            .associate { item ->
                val splitUp = item.split(SLOT_AFFORDANCES_DELIMITER)
                check(splitUp.size == 2)
                val slotId = splitUp[0]
                val affordanceIds = splitUp[1].split(AFFORDANCE_DELIMITER)
                slotId to affordanceIds
            }
    }

    /**
     * Emits an event each time a Backup & Restore restoration job is completed. Does not emit an
     * initial value.
     */
    private val backupRestorationEvents: Flow<Unit> =
        broadcastDispatcher.broadcastFlow(
            filter = IntentFilter(BackupHelper.ACTION_RESTORE_FINISHED),
            flags = Context.RECEIVER_NOT_EXPORTED,
            permission = BackupHelper.PERMISSION_SELF,
        )

    override val selections: Flow<Map<String, List<String>>> =
        combine(
                userId,
                backupRestorationEvents.onStart {
                    // We emit an initial event to make sure that the combine emits at least once,
                    // even if we never get a Backup & Restore restoration event (which is the most
                    // common case anyway as restoration really only happens on initial device
                    // setup).
                    emit(Unit)
                }
            ) { _, _ ->
            }
            .flatMapLatest {
                conflatedCallbackFlow {
                    // We want to instantiate a new SharedPreferences instance each time either the
                    // user ID changes or we have a backup & restore restoration event. The reason
                    // is that our sharedPrefs instance needs to be replaced with a new one as it
                    // depends on the user ID and when the B&R job completes, the backing file is
                    // replaced but the existing instance still has a stale in-memory cache.
                    sharedPrefs = instantiateSharedPrefs()

                    val listener =
                        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                            trySend(getSelections())
                        }

                    sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                    send(getSelections())

                    awaitClose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
                }
            }

    override fun getSelections(): Map<String, List<String>> {
        // If the custom shortcuts feature is not enabled, ignore prior selections and use defaults
        if (!context.resources.getBoolean(R.bool.custom_lockscreen_shortcuts_enabled)) {
            return defaults
        }

        val slotKeys = sharedPrefs.all.keys.filter { it.startsWith(KEY_PREFIX_SLOT) }
        val result =
            slotKeys
                .associate { key ->
                    val slotId = key.substring(KEY_PREFIX_SLOT.length)
                    val value = sharedPrefs.getString(key, null)
                    val affordanceIds =
                        if (!value.isNullOrEmpty()) {
                            value.split(AFFORDANCE_DELIMITER)
                        } else {
                            emptyList()
                        }
                    slotId to affordanceIds
                }
                .toMutableMap()

        // If the result map is missing keys, it means that the system has never set anything for
        // those slots. This is where we need examine our defaults and see if there should be a
        // default value for the affordances in the slot IDs that are missing from the result.
        //
        // Once the user makes any selection for a slot, even when they select "None", this class
        // will persist a key for that slot ID. In the case of "None", it will have a value of the
        // empty string. This is why this system works.
        defaults.forEach { (slotId, affordanceIds) ->
            if (!result.containsKey(slotId)) {
                result[slotId] = affordanceIds
            }
        }

        return result
    }

    override fun setSelections(
        slotId: String,
        affordanceIds: List<String>,
    ) {
        val key = "$KEY_PREFIX_SLOT$slotId"
        val value = affordanceIds.joinToString(AFFORDANCE_DELIMITER)
        sharedPrefs.edit().putString(key, value).apply()
    }

    private fun instantiateSharedPrefs(): SharedPreferences {
        return userFileManager.getSharedPreferences(
            FILE_NAME,
            Context.MODE_PRIVATE,
            userTracker.userId,
        )
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordancePrimaryUserSelectionManager"
        const val FILE_NAME = "quick_affordance_selections"
        private const val KEY_PREFIX_SLOT = "slot_"
        private const val SLOT_AFFORDANCES_DELIMITER = ":"
        private const val AFFORDANCE_DELIMITER = ","
    }
}
