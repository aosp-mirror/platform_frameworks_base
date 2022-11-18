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
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.android.systemui.R
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * Manages and provides access to the current "selections" of keyguard quick affordances, answering
 * the question "which affordances should the keyguard show?".
 */
@SysUISingleton
class KeyguardQuickAffordanceSelectionManager
@Inject
constructor(
    @Application context: Context,
    private val userFileManager: UserFileManager,
    private val userTracker: UserTracker,
) {

    private val sharedPrefs: SharedPreferences
        get() =
            userFileManager.getSharedPreferences(
                FILE_NAME,
                Context.MODE_PRIVATE,
                userTracker.userId,
            )

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

    /** IDs of affordances to show, indexed by slot ID, and sorted in descending priority order. */
    val selections: Flow<Map<String, List<String>>> =
        userId.flatMapLatest {
            conflatedCallbackFlow {
                val listener =
                    SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                        trySend(getSelections())
                    }

                sharedPrefs.registerOnSharedPreferenceChangeListener(listener)
                send(getSelections())

                awaitClose { sharedPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }
        }

    /**
     * Returns a snapshot of the IDs of affordances to show, indexed by slot ID, and sorted in
     * descending priority order.
     */
    fun getSelections(): Map<String, List<String>> {
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

    /**
     * Updates the IDs of affordances to show at the slot with the given ID. The order of affordance
     * IDs should be descending priority order.
     */
    fun setSelections(
        slotId: String,
        affordanceIds: List<String>,
    ) {
        val key = "$KEY_PREFIX_SLOT$slotId"
        val value = affordanceIds.joinToString(AFFORDANCE_DELIMITER)
        sharedPrefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceSelectionManager"
        @VisibleForTesting const val FILE_NAME = "quick_affordance_selections"
        private const val KEY_PREFIX_SLOT = "slot_"
        private const val SLOT_AFFORDANCES_DELIMITER = ":"
        private const val AFFORDANCE_DELIMITER = ","
    }
}
