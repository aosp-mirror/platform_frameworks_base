/*
 *  Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.content.Intent
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.shared.quickaffordance.KeyguardQuickAffordanceToggleState
import kotlinx.coroutines.flow.Flow

/** Defines interface that can act as data source for a single quick affordance model. */
interface KeyguardQuickAffordanceConfig {

    /** Unique identifier for this quick affordance. It must be globally unique. */
    val key: String

    /** The observable [State] of the affordance. */
    val state: Flow<State>

    /**
     * Notifies that the affordance was clicked by the user.
     *
     * @param expandable An [Expandable] to use when animating dialogs or activities
     * @return An [OnClickedResult] telling the caller what to do next
     */
    fun onQuickAffordanceClicked(expandable: Expandable?): OnClickedResult

    /**
     * Encapsulates the state of a "quick affordance" in the keyguard bottom area (for example, a
     * button on the lock-screen).
     */
    sealed class State {

        /** No affordance should show up. */
        object Hidden : State()

        /** An affordance is visible. */
        data class Visible(
            /** An icon for the affordance. */
            val icon: Icon,
            /** The toggle state for the affordance. */
            val toggle: KeyguardQuickAffordanceToggleState =
                KeyguardQuickAffordanceToggleState.NotSupported,
        ) : State()
    }

    sealed class OnClickedResult {
        /**
         * Returning this as a result from the [onQuickAffordanceClicked] method means that the
         * implementation has taken care of the click, the system will do nothing.
         */
        object Handled : OnClickedResult()

        /**
         * Returning this as a result from the [onQuickAffordanceClicked] method means that the
         * implementation has _not_ taken care of the click and the system should start an activity
         * using the given [Intent].
         */
        data class StartActivity(
            val intent: Intent,
            val canShowWhileLocked: Boolean,
        ) : OnClickedResult()
    }
}
