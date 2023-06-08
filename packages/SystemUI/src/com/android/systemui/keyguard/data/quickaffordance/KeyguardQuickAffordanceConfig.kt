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

import android.app.AlertDialog
import android.content.Intent
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import com.android.systemui.shared.customization.data.content.CustomizationProviderContract as Contract
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
     * Returns the [PickerScreenState] representing the affordance in the settings or selector
     * experience.
     */
    suspend fun getPickerScreenState(): PickerScreenState = PickerScreenState.Default()

    /**
     * Notifies that the affordance was clicked by the user.
     *
     * @param expandable An [Expandable] to use when animating dialogs or activities
     * @return An [OnTriggeredResult] telling the caller what to do next
     */
    fun onTriggered(expandable: Expandable?): OnTriggeredResult

    /**
     * Encapsulates the state of a quick affordance within the context of the settings or selector
     * experience.
     */
    sealed class PickerScreenState {

        /** The picker shows the item for selecting this affordance as it normally would. */
        data class Default(
            /** Optional [Intent] to use to start an activity to configure this affordance. */
            val configureIntent: Intent? = null,
        ) : PickerScreenState()

        /**
         * The picker does not show an item for selecting this affordance as it is not supported on
         * the device at all. For example, missing hardware requirements.
         */
        object UnavailableOnDevice : PickerScreenState()

        /**
         * The picker shows the item for selecting this affordance as disabled. Clicking on it will
         * show the given instructions to the user. If [actionText] and [actionComponentName] are
         * provided (optional) a button will be shown to open an activity to help the user complete
         * the steps described in the instructions.
         */
        data class Disabled(
            /** List of human-readable instructions for setting up the quick affordance. */
            val instructions: List<String>,
            /**
             * Optional text to display on a button that the user can click to start a flow to go
             * and set up the quick affordance and make it enabled.
             */
            val actionText: String? = null,
            /**
             * Optional component name to be able to build an `Intent` that opens an `Activity` for
             * the user to be able to set up the quick affordance and make it enabled.
             *
             * This is either just an action for the `Intent` or a package name and action,
             * separated by
             * [Contract.LockScreenQuickAffordances.AffordanceTable.COMPONENT_NAME_SEPARATOR] for
             * convenience, you can use the [componentName] function.
             */
            val actionComponentName: String? = null,
        ) : PickerScreenState() {
            init {
                check(instructions.isNotEmpty()) { "Instructions must not be empty!" }
                check(
                    (actionText.isNullOrEmpty() && actionComponentName.isNullOrEmpty()) ||
                        (!actionText.isNullOrEmpty() && !actionComponentName.isNullOrEmpty())
                ) {
                    "actionText and actionComponentName must either both be null/empty or both be" +
                        " non-empty!"
                }
            }
        }
    }

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

        /**
         * Returning this as a result from the [onTriggered] method means that the implementation
         * has _not_ taken care of the action and the system should show a Dialog using the given
         * [AlertDialog] and [Expandable].
         */
        data class ShowDialog(
            val dialog: AlertDialog,
            val expandable: Expandable?,
        ) : OnTriggeredResult()
    }

    companion object {
        fun componentName(
            packageName: String? = null,
            action: String?,
        ): String? {
            return when {
                action.isNullOrEmpty() -> null
                !packageName.isNullOrEmpty() ->
                    "$packageName${Contract.LockScreenQuickAffordances.AffordanceTable
                        .COMPONENT_NAME_SEPARATOR}$action"
                else -> action
            }
        }
    }
}
