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

import kotlinx.coroutines.flow.Flow

/**
 * Defines interface for classes that manage and provide access to the current "selections" of
 * keyguard quick affordances, answering the question "which affordances should the keyguard show?".
 */
interface KeyguardQuickAffordanceSelectionManager {

    /** IDs of affordances to show, indexed by slot ID, and sorted in descending priority order. */
    val selections: Flow<Map<String, List<String>>>

    /**
     * Returns a snapshot of the IDs of affordances to show, indexed by slot ID, and sorted in
     * descending priority order.
     */
    fun getSelections(): Map<String, List<String>>

    /**
     * Updates the IDs of affordances to show at the slot with the given ID. The order of affordance
     * IDs should be descending priority order.
     */
    fun setSelections(
        slotId: String,
        affordanceIds: List<String>,
    )

    companion object {
        const val FILE_NAME = "quick_affordance_selections"
    }
}
