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

package com.android.systemui.statusbar.policy

import android.app.admin.DeviceAdminInfo
import android.content.ComponentName
import android.graphics.drawable.Drawable
import java.io.PrintWriter

/** A fake [SecurityController] to be used in tests. */
class FakeSecurityController(
    private val fakeState: FakeState = FakeState(),
) : SecurityController {
    private val callbacks = LinkedHashSet<SecurityController.SecurityControllerCallback>()

    override fun addCallback(callback: SecurityController.SecurityControllerCallback) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: SecurityController.SecurityControllerCallback) {
        callbacks.remove(callback)
    }

    /** Update [fakeState], then notify the callbacks. */
    fun updateState(f: FakeState.() -> Unit) {
        fakeState.f()
        callbacks.forEach { it.onStateChanged() }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {}

    override fun isDeviceManaged(): Boolean = fakeState.isDeviceManaged

    override fun hasProfileOwner(): Boolean = fakeState.hasProfileOwner

    override fun hasWorkProfile(): Boolean = fakeState.hasWorkProfile

    override fun isWorkProfileOn(): Boolean = fakeState.isWorkProfileOn

    override fun isProfileOwnerOfOrganizationOwnedDevice(): Boolean =
        fakeState.isProfileOwnerOfOrganizationOwnedDevice

    override fun getDeviceOwnerName(): String? = fakeState.deviceOwnerName

    override fun getProfileOwnerName(): String? = fakeState.profileOwnerName

    override fun getDeviceOwnerOrganizationName(): String? = fakeState.deviceOwnerOrganizationName

    override fun getWorkProfileOrganizationName(): String? = fakeState.workProfileOrganizationName

    override fun getDeviceOwnerComponentOnAnyUser(): ComponentName? =
        fakeState.deviceOwnerComponentOnAnyUser

    override fun getDeviceOwnerType(admin: ComponentName?): Int = 0

    override fun isNetworkLoggingEnabled(): Boolean = fakeState.isNetworkLoggingEnabled

    override fun isVpnEnabled(): Boolean = fakeState.isVpnEnabled

    override fun isVpnRestricted(): Boolean = fakeState.isVpnRestricted

    override fun isVpnBranded(): Boolean = fakeState.isVpnBranded

    override fun getPrimaryVpnName(): String? = fakeState.primaryVpnName

    override fun getWorkProfileVpnName(): String? = fakeState.workProfileVpnName

    override fun hasCACertInCurrentUser(): Boolean = fakeState.hasCACertInCurrentUser

    override fun hasCACertInWorkProfile(): Boolean = fakeState.hasCACertInWorkProfile

    override fun onUserSwitched(newUserId: Int) {}

    override fun isParentalControlsEnabled(): Boolean = fakeState.isParentalControlsEnabled

    override fun getDeviceAdminInfo(): DeviceAdminInfo? = fakeState.deviceAdminInfo

    override fun getIcon(info: DeviceAdminInfo?): Drawable? = null

    override fun getLabel(info: DeviceAdminInfo?): CharSequence? = null

    class FakeState(
        var isDeviceManaged: Boolean = false,
        var hasProfileOwner: Boolean = false,
        var hasWorkProfile: Boolean = false,
        var isWorkProfileOn: Boolean = false,
        var isProfileOwnerOfOrganizationOwnedDevice: Boolean = false,
        var deviceOwnerName: String? = null,
        var profileOwnerName: String? = null,
        var deviceOwnerOrganizationName: String? = null,
        var workProfileOrganizationName: String? = null,
        var deviceOwnerComponentOnAnyUser: ComponentName? = null,
        var isNetworkLoggingEnabled: Boolean = false,
        var isVpnEnabled: Boolean = false,
        var isVpnRestricted: Boolean = false,
        var isVpnBranded: Boolean = false,
        var primaryVpnName: String? = null,
        var workProfileVpnName: String? = null,
        var hasCACertInCurrentUser: Boolean = false,
        var hasCACertInWorkProfile: Boolean = false,
        var isParentalControlsEnabled: Boolean = false,
        var deviceAdminInfo: DeviceAdminInfo? = null,
    )
}
