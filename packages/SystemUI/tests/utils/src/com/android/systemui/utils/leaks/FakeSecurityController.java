/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.app.admin.DeviceAdminInfo;
import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.testing.LeakCheck;

import com.android.systemui.statusbar.policy.SecurityController;
import com.android.systemui.statusbar.policy.SecurityController.SecurityControllerCallback;

public class FakeSecurityController extends BaseLeakChecker<SecurityControllerCallback>
        implements SecurityController {
    public FakeSecurityController(LeakCheck test) {
        super(test, "security");
    }

    @Override
    public boolean isDeviceManaged() {
        return false;
    }

    @Override
    public boolean hasProfileOwner() {
        return false;
    }

    @Override
    public boolean hasWorkProfile() {
        return false;
    }

    @Override
    public boolean isWorkProfileOn() {
        return false;
    }

    @Override
    public boolean isProfileOwnerOfOrganizationOwnedDevice() {
        return false;
    }

    @Override
    public String getDeviceOwnerName() {
        return null;
    }

    @Override
    public String getProfileOwnerName() {
        return null;
    }

    @Override
    public CharSequence getDeviceOwnerOrganizationName() {
        return null;
    }

    @Override
    public CharSequence getWorkProfileOrganizationName() {
        return null;
    }

    @Override
    public ComponentName getDeviceOwnerComponentOnAnyUser() {
        return null;
    }

    @Override
    public int getDeviceOwnerType(ComponentName admin) {
        return 0;
    }

    @Override
    public boolean isFinancedDevice() {
        return false;
    }

    @Override
    public boolean isNetworkLoggingEnabled() {
        return false;
    }

    @Override
    public boolean isVpnEnabled() {
        return false;
    }

    @Override
    public boolean isVpnRestricted() {
        return false;
    }

    @Override
    public boolean isVpnBranded() {
        return false;
    }

    @Override
    public boolean isVpnValidated() {
        return false;
    }

    @Override
    public String getPrimaryVpnName() {
        return null;
    }

    @Override
    public String getWorkProfileVpnName() {
        return null;
    }

    @Override
    public boolean hasCACertInCurrentUser() {
        return false;
    }

    @Override
    public boolean hasCACertInWorkProfile() {
        return false;
    }

    @Override
    public void onUserSwitched(int newUserId) {

    }

    @Override
    public boolean isParentalControlsEnabled() {
        return false;
    }

    @Override
    public DeviceAdminInfo getDeviceAdminInfo() {
        return null;
    }

    @Override
    public Drawable getIcon(DeviceAdminInfo info) {
        return null;
    }

    @Override
    public CharSequence getLabel(DeviceAdminInfo info) {
        return null;
    }
}
