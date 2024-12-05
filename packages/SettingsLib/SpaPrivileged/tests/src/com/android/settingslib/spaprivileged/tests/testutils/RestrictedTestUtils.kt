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

package com.android.settingslib.spaprivileged.tests.testutils

import android.app.admin.EnforcingAdmin
import android.app.admin.UnknownAuthority
import android.os.UserHandle
import android.security.advancedprotection.AdvancedProtectionManager.ADVANCED_PROTECTION_SYSTEM_ENTITY
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByAdmin
import com.android.settingslib.spaprivileged.model.enterprise.BlockedByEcm
import com.android.settingslib.spaprivileged.model.enterprise.RestrictedMode
import com.android.settingslib.spaprivileged.model.enterprise.RestrictionsProvider

class FakeBlockedByAdmin : BlockedByAdmin {
    var sendShowAdminSupportDetailsIntentIsCalled = false

    override fun getSummary(checked: Boolean?) = SUMMARY

    override fun sendShowAdminSupportDetailsIntent() {
        sendShowAdminSupportDetailsIntentIsCalled = true
    }

    companion object {
        const val SUMMARY = "Blocked by admin"
    }
}

class FakeBlockedByEcm : BlockedByEcm {
    var showRestrictedSettingsDetailsIsCalled = false

    override fun showRestrictedSettingsDetails() {
        showRestrictedSettingsDetailsIsCalled = true
    }

    companion object {
        const val SUMMARY = "Disabled"
    }
}

class FakeRestrictionsProvider : RestrictionsProvider {
    var restrictedMode: RestrictedMode? = null

    @Composable
    override fun restrictedModeState() = stateOf(restrictedMode)
}

fun getEnforcingAdminAdvancedProtection(packageName: String, userId: Int): EnforcingAdmin =
    EnforcingAdmin(packageName, UnknownAuthority(ADVANCED_PROTECTION_SYSTEM_ENTITY),
        UserHandle.of(userId))

fun getEnforcingAdminNotAdvancedProtection(packageName: String, userId: Int): EnforcingAdmin =
    EnforcingAdmin(packageName, UnknownAuthority.UNKNOWN_AUTHORITY, UserHandle.of(userId))
