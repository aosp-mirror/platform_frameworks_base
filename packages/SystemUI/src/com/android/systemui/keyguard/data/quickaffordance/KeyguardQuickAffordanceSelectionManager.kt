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

import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages and provides access to the current "selections" of keyguard quick affordances, answering
 * the question "which affordances should the keyguard show?".
 */
@SysUISingleton
class KeyguardQuickAffordanceSelectionManager @Inject constructor() {

    // TODO(b/254858695): implement a persistence layer (database).
    private val _selections = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    /** IDs of affordances to show, indexed by slot ID, and sorted in descending priority order. */
    val selections: Flow<Map<String, List<String>>> = _selections.asStateFlow()

    /**
     * Returns a snapshot of the IDs of affordances to show, indexed by slot ID, and sorted in
     * descending priority order.
     */
    suspend fun getSelections(): Map<String, List<String>> {
        return _selections.value
    }

    /**
     * Updates the IDs of affordances to show at the slot with the given ID. The order of affordance
     * IDs should be descending priority order.
     */
    suspend fun setSelections(
        slotId: String,
        affordanceIds: List<String>,
    ) {
        // Must make a copy of the map and update it, otherwise, the MutableStateFlow won't emit
        // when we set its value to the same instance of the original map, even if we change the
        // map by updating the value of one of its keys.
        val copy = _selections.value.toMutableMap()
        copy[slotId] = affordanceIds
        _selections.value = copy
    }
}
