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
 */

package com.android.systemui.security.data.model

import android.graphics.drawable.Drawable
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.policy.SecurityController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** The security info exposed by [com.android.systemui.statusbar.policy.SecurityController]. */
// TODO(b/242040009): Consider splitting this model into smaller submodels.
data class SecurityModel(
    val isDeviceManaged: Boolean,
    val hasWorkProfile: Boolean,
    val isWorkProfileOn: Boolean,
    val isProfileOwnerOfOrganizationOwnedDevice: Boolean,
    val deviceOwnerOrganizationName: String?,
    val workProfileOrganizationName: String?,
    val isNetworkLoggingEnabled: Boolean,
    val isVpnBranded: Boolean,
    val primaryVpnName: String?,
    val workProfileVpnName: String?,
    val hasCACertInCurrentUser: Boolean,
    val hasCACertInWorkProfile: Boolean,
    val isParentalControlsEnabled: Boolean,
    val deviceAdminIcon: Drawable?,
) {
    companion object {
        /** Create a [SecurityModel] from the current [securityController] state. */
        suspend fun create(
            securityController: SecurityController,
            @Background bgDispatcher: CoroutineDispatcher,
        ): SecurityModel {
            return withContext(bgDispatcher) { create(securityController) }
        }

        /**
         * Create a [SecurityModel] from the current [securityController] state.
         *
         * Important: This method should be called from a background thread as this will do a lot of
         * binder calls.
         */
        @JvmStatic
        @VisibleForTesting
        fun create(securityController: SecurityController): SecurityModel {
            val deviceAdminInfo =
                if (securityController.isParentalControlsEnabled) {
                    securityController.deviceAdminInfo
                } else {
                    null
                }

            return SecurityModel(
                isDeviceManaged = securityController.isDeviceManaged,
                hasWorkProfile = securityController.hasWorkProfile(),
                isWorkProfileOn = securityController.isWorkProfileOn,
                isProfileOwnerOfOrganizationOwnedDevice =
                    securityController.isProfileOwnerOfOrganizationOwnedDevice,
                deviceOwnerOrganizationName =
                    securityController.deviceOwnerOrganizationName?.toString(),
                workProfileOrganizationName =
                    securityController.workProfileOrganizationName?.toString(),
                isNetworkLoggingEnabled = securityController.isNetworkLoggingEnabled,
                isVpnBranded = securityController.isVpnBranded,
                primaryVpnName = securityController.primaryVpnName,
                workProfileVpnName = securityController.workProfileVpnName,
                hasCACertInCurrentUser = securityController.hasCACertInCurrentUser(),
                hasCACertInWorkProfile = securityController.hasCACertInWorkProfile(),
                isParentalControlsEnabled = securityController.isParentalControlsEnabled,
                deviceAdminIcon = securityController.getIcon(deviceAdminInfo),
            )
        }
    }
}
