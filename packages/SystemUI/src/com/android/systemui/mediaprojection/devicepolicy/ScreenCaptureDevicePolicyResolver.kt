/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.systemui.mediaprojection.devicepolicy

import android.app.admin.DevicePolicyManager
import android.os.UserHandle
import android.os.UserManager
import javax.inject.Inject

/**
 * Utility class to resolve if screen capture allowed for a particular target app/host app pair. It
 * caches the state of the policies, so you need to create a new instance of this class if you want
 * to react to updated policies state.
 */
class ScreenCaptureDevicePolicyResolver
@Inject
constructor(
    private val devicePolicyManager: DevicePolicyManager,
    private val userManager: UserManager,
    @PersonalProfile private val personalProfileUserHandle: UserHandle,
    @WorkProfile private val workProfileUserHandle: UserHandle?
) {

    /**
     * Returns true if [hostAppUserHandle] is allowed to perform screen capture of
     * [targetAppUserHandle]
     */
    fun isScreenCaptureAllowed(
        targetAppUserHandle: UserHandle,
        hostAppUserHandle: UserHandle,
    ): Boolean {
        if (hostAppUserHandle.isWorkProfile() && workProfileScreenCaptureDisabled) {
            // Disable screen capturing as host apps should not capture the screen
            return false
        }

        if (!hostAppUserHandle.isWorkProfile() && personalProfileScreenCaptureDisabled) {
            // Disable screen capturing as personal apps should not capture the screen
            return false
        }

        if (targetAppUserHandle.isWorkProfile()) {
            // Work profile target
            if (workProfileScreenCaptureDisabled) {
                // Do not allow sharing work profile apps as work profile capturing is disabled
                return false
            }
        } else {
            // Personal profile target
            if (hostAppUserHandle.isWorkProfile() && disallowSharingIntoManagedProfile) {
                // Do not allow sharing of personal apps into work profile apps
                return false
            }

            if (personalProfileScreenCaptureDisabled) {
                // Disable screen capturing as personal apps should not be captured
                return false
            }
        }

        return true
    }

    /**
     * Returns true if [hostAppUserHandle] is NOT allowed to capture an app from any profile,
     * could be useful to finish the screen capture flow as soon as possible when the screen
     * could not be captured at all.
     */
    fun isScreenCaptureCompletelyDisabled(hostAppUserHandle: UserHandle): Boolean {
        val isWorkAppsCaptureDisabled =
                if (workProfileUserHandle != null) {
                    !isScreenCaptureAllowed(
                            targetAppUserHandle = workProfileUserHandle,
                            hostAppUserHandle = hostAppUserHandle
                    )
                } else true

        val isPersonalAppsCaptureDisabled =
                !isScreenCaptureAllowed(
                        targetAppUserHandle = personalProfileUserHandle,
                        hostAppUserHandle = hostAppUserHandle
                )

        return isWorkAppsCaptureDisabled && isPersonalAppsCaptureDisabled
    }

    private val personalProfileScreenCaptureDisabled: Boolean by lazy {
        devicePolicyManager.getScreenCaptureDisabled(
            /* admin */ null,
            personalProfileUserHandle.identifier
        )
    }

    private val workProfileScreenCaptureDisabled: Boolean by lazy {
        workProfileUserHandle?.let {
            devicePolicyManager.getScreenCaptureDisabled(/* admin */ null, it.identifier)
        }
            ?: false
    }

    private val disallowSharingIntoManagedProfile: Boolean by lazy {
        workProfileUserHandle?.let {
            userManager.hasUserRestrictionForUser(
                UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
                it
            )
        }
            ?: false
    }

    private fun UserHandle?.isWorkProfile(): Boolean = this == workProfileUserHandle
}
