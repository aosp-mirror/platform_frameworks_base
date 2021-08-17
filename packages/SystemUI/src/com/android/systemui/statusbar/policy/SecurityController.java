/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import android.app.admin.DeviceAdminInfo;
import android.content.ComponentName;
import android.graphics.drawable.Drawable;

import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.SecurityController.SecurityControllerCallback;

public interface SecurityController extends CallbackController<SecurityControllerCallback>,
        Dumpable {
    /** Whether the device has device owner, even if not on this user. */
    boolean isDeviceManaged();
    boolean hasProfileOwner();
    boolean hasWorkProfile();
    /** Whether the work profile is turned on. */
    boolean isWorkProfileOn();
    /** Whether this device is organization-owned with a work profile **/
    boolean isProfileOwnerOfOrganizationOwnedDevice();
    String getDeviceOwnerName();
    String getProfileOwnerName();
    CharSequence getDeviceOwnerOrganizationName();
    CharSequence getWorkProfileOrganizationName();
    /** Device owner component even if not on this user. **/
    ComponentName getDeviceOwnerComponentOnAnyUser();
    /** Device owner type for a device owner. **/
    int getDeviceOwnerType(ComponentName admin);
    boolean isNetworkLoggingEnabled();
    boolean isVpnEnabled();
    boolean isVpnRestricted();
    /** Whether the VPN app should use branded VPN iconography.  */
    boolean isVpnBranded();
    String getPrimaryVpnName();
    String getWorkProfileVpnName();
    boolean hasCACertInCurrentUser();
    boolean hasCACertInWorkProfile();
    void onUserSwitched(int newUserId);
    /** Whether or not parental controls is enabled */
    boolean isParentalControlsEnabled();
    /** DeviceAdminInfo for active admin */
    DeviceAdminInfo getDeviceAdminInfo();
    /** Icon for admin */
    Drawable getIcon(DeviceAdminInfo info);
    /** Label for admin */
    CharSequence getLabel(DeviceAdminInfo info);

    public interface SecurityControllerCallback {
        void onStateChanged();
    }

}
