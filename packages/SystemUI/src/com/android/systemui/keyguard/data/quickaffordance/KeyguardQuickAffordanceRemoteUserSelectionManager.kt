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
import android.os.UserHandle
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.customization.data.content.CustomizationProviderClient
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Manages and provides access to the current "selections" of keyguard quick affordances, answering
 * the question "which affordances should the keyguard show?" for users associated with other System
 * UI processes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class KeyguardQuickAffordanceRemoteUserSelectionManager
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val userTracker: UserTracker,
    private val clientFactory: KeyguardQuickAffordanceProviderClientFactory,
    private val userHandle: UserHandle,
) : KeyguardQuickAffordanceSelectionManager {

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

    private val clientOrNull: StateFlow<CustomizationProviderClient?> =
        userId
            .distinctUntilChanged()
            .map { selectedUserId ->
                if (userHandle.isSystem && userHandle.identifier != selectedUserId) {
                    clientFactory.create()
                } else {
                    null
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = null,
            )

    private val _selections: StateFlow<Map<String, List<String>>> =
        clientOrNull
            .flatMapLatest { client ->
                client?.observeSelections()?.map { selections ->
                    buildMap<String, List<String>> {
                        selections.forEach { selection ->
                            val slotId = selection.slotId
                            val affordanceIds = (get(slotId) ?: emptyList()).toMutableList()
                            affordanceIds.add(selection.affordanceId)
                            put(slotId, affordanceIds)
                        }
                    }
                }
                    ?: emptyFlow()
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = emptyMap(),
            )

    override val selections: Flow<Map<String, List<String>>> = _selections

    override fun getSelections(): Map<String, List<String>> {
        return _selections.value
    }

    override fun setSelections(slotId: String, affordanceIds: List<String>) {
        clientOrNull.value?.let { client ->
            scope.launch {
                client.deleteAllSelections(slotId = slotId)
                affordanceIds.forEach { affordanceId ->
                    client.insertSelection(slotId = slotId, affordanceId = affordanceId)
                }
            }
        }
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceMultiUserSelectionManager"
    }
}
