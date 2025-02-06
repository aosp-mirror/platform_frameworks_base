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

import android.annotation.Nullable;
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

    boolean isFinancedDevice();

    /** Device owner component even if not on this user. **/
    ComponentName getDeviceOwnerComponentOnAnyUser();
    // TODO(b/259908270): remove
    /** Device owner type for a device owner. **/
    @Deprecated
    int getDeviceOwnerType(ComponentName admin);
    boolean isNetworkLoggingEnabled();
    boolean isVpnEnabled();
    boolean isVpnRestricted();
    /** Whether the VPN network is validated. */
    boolean isVpnValidated();
    /** Whether the VPN app should use branded VPN iconography.  */
    boolean isVpnBranded();
    String getPrimaryVpnName();
    String getWorkProfileVpnName();
    boolean hasCACertInCurrentUser();
    boolean hasCACertInWorkProfile();
    void onUserSwitched(int newUserId);
    /** Whether or not parental controls is enabled */
    boolean isParentalControlsEnabled();

    // TODO(b/382034839): Remove during the cleanup of deprecate_dpm_supervision_apis.
    /**
     * DeviceAdminInfo for active admin
     * @deprecated No longer needed.
     */
    @Deprecated
    DeviceAdminInfo getDeviceAdminInfo();

    // TODO(b/382034839): Remove during the cleanup of deprecate_dpm_supervision_apis.
    /**
     * Icon for admin
     * @deprecated Use {@link #getIcon()} instead.
     */
    @Deprecated
    Drawable getIcon(DeviceAdminInfo info);
    /** Icon for admin */
    @Nullable
    Drawable getIcon();

    // TODO(b/382034839): Remove during the cleanup of deprecate_dpm_supervision_apis.
    /**
     * Label for admin
     * @deprecated Use {@link #getLabel()} instead.
     */
    @Deprecated
    CharSequence getLabel(DeviceAdminInfo info);
    /** Label for admin */
    @Nullable
    CharSequence getLabel();

    public interface SecurityControllerCallback {
        void onStateChanged();
    }

}
