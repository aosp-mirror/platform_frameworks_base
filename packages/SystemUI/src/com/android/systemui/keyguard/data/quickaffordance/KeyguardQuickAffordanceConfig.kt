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
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.systemui.res.R
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.keyguard.shared.quickaffordance.ActivationState
import kotlinx.coroutines.flow.Flow

/** Defines interface that can act as data source for a single quick affordance model. */
interface KeyguardQuickAffordanceConfig {

    /** Unique identifier for this quick affordance. It must be globally unique. */
    val key: String

    val pickerIconResourceId: Int

    /**
     * The ever-changing state of the affordance.
     *
     * Used to populate the lock screen.
     */
    val lockScreenState: Flow<LockScreenState>

    /**
     * Returns a user-visible [String] that should be shown as the name for the option in the
     * wallpaper picker / settings app to select this quick affordance.
     */
    fun pickerName(): String

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
         * show the given instructions to the user. If [actionText] and [actionIntent] are provided
         * (optional) a button will be shown to open an activity to help the user complete the steps
         * described in the instructions.
         */
        data class Disabled(
            /** Human-readable explanation as to why the quick affordance is current disabled. */
            val explanation: String,
            /**
             * Optional text to display on a button that the user can click to start a flow to go
             * and set up the quick affordance and make it enabled.
             */
            val actionText: String? = null,
            /**
             * Optional [Intent] that opens an `Activity` for the user to be able to set up the
             * quick affordance and make it enabled.
             */
            val actionIntent: Intent? = null,
        ) : PickerScreenState() {
            init {
                check(explanation.isNotEmpty()) { "Explanation must not be empty!" }
                check(
                    (actionText.isNullOrEmpty() && actionIntent == null) ||
                        (!actionText.isNullOrEmpty() && actionIntent != null)
                ) {
                    """
                        actionText and actionIntent must either both be null/empty or both be
                        non-null and non-empty!
                    """
                        .trimIndent()
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

        /**
         * Returns an [Intent] that can be used to start an activity that opens the app store app to
         * a page showing the app with the passed-in [packageName].
         *
         * If the feature isn't enabled on this device/variant/configuration, a `null` will be
         * returned.
         */
        fun appStoreIntent(context: Context, packageName: String?): Intent? {
            if (packageName.isNullOrEmpty()) {
                return null
            }

            val appStorePackageName = context.getString(R.string.config_appStorePackageName)
            val linkTemplate = context.getString(R.string.config_appStoreAppLinkTemplate)
            if (appStorePackageName.isEmpty() || linkTemplate.isEmpty()) {
                return null
            }

            check(linkTemplate.contains(APP_PACKAGE_NAME_PLACEHOLDER))

            return Intent(Intent.ACTION_VIEW).apply {
                setPackage(appStorePackageName)
                data = Uri.parse(linkTemplate.replace(APP_PACKAGE_NAME_PLACEHOLDER, packageName))
            }
        }

        private const val APP_PACKAGE_NAME_PLACEHOLDER = "\$packageName"
    }
}
