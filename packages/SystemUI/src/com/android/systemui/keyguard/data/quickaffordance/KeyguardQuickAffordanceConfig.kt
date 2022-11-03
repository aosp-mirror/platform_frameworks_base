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

import android.content.Intent
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import kotlinx.coroutines.flow.Flow

/** Defines interface that can act as data source for a single quick affordance model. */
interface KeyguardQuickAffordanceConfig {

    /** Unique identifier for this quick affordance. It must be globally unique. */
    val key: String

    val pickerName: String

    val pickerIconResourceId: Int

    /**
     * The ever-changing state of the affordance.
     *
     * Used to populate the lock screen.
     */
    val lockScreenState: Flow<LockScreenState>

    /**
     * Notifies that the affordance was clicked by the user.
     *
     * @param expandable An [Expandable] to use when animating dialogs or activities
     * @return An [OnTriggeredResult] telling the caller what to do next
     */
    fun onTriggered(expandable: Expandable?): OnTriggeredResult

    /**
     * Encapsulates the state of a "quick affordance" in the keyguard bottom area (for example, a
     * button on the lock-screen).
     */
    sealed class LockScreenState {

        /** No affordance should show up. */
        object Hidden : LockScreenState()

        /** An affordance is visible. */
        data class Visible(
            /** An icon for the affordance. */
            val icon: Icon,
            /** The activation state of the affordance. */
            val activationState: ActivationState = ActivationState.NotSupported,
        ) : LockScreenState()
    }

    sealed class OnTriggeredResult {
        /**
         * Returning this as a result from the [onTriggered] method means that the implementation
         * has taken care of the action, the system will do nothing.
         */
        object Handled : OnTriggeredResult()

        /**
         * Returning this as a result from the [onTriggered] method means that the implementation
         * has _not_ taken care of the action and the system should start an activity using the
         * given [Intent].
         */
        data class StartActivity(
            val intent: Intent,
            val canShowWhileLocked: Boolean,
        ) : OnTriggeredResult()
    }
}
