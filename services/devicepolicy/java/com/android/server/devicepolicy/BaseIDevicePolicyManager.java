/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.devicepolicy;

import android.app.admin.DevicePolicyManager;
import android.app.admin.IDevicePolicyManager;
import android.app.admin.StartInstallingUpdateCallback;
import android.content.ComponentName;
import android.os.ParcelFileDescriptor;

import com.android.server.SystemService;

import java.util.Collections;
import java.util.List;

/**
 * Defines the required interface for IDevicePolicyManager implemenation.
 *
 * <p>The interface consists of public parts determined by {@link IDevicePolicyManager} and also
 * several package private methods required by internal infrastructure.
 *
 * <p>Whenever adding an AIDL method to {@link IDevicePolicyManager}, an empty override method
 * should be added here to avoid build breakage in downstream branches.
 */
abstract class BaseIDevicePolicyManager extends IDevicePolicyManager.Stub {
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} during the various boot phases.
     *
     * @see {@link SystemService#onBootPhase}.
     */
    abstract void systemReady(int phase);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a new user starts.
     *
     * @see {@link SystemService#onStartUser}
     */
    abstract void handleStartUser(int userId);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a user is being unlocked.
     *
     * @see {@link SystemService#onUnlockUser}
     */
    abstract void handleUnlockUser(int userId);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a user is being stopped.
     *
     * @see {@link SystemService#onStopUser}
     */
    abstract void handleStopUser(int userId);

    public void clearSystemUpdatePolicyFreezePeriodRecord() {
    }

    @Override
    public long forceNetworkLogs() {
        return 0;
    }

    @Override
    public long forceSecurityLogs() {
        return 0;
    }

    @Override
    public boolean checkDeviceIdentifierAccess(String packageName, int userHandle, int pid,
            int uid) {
        return false;
    }

    @Override
    public int setGlobalPrivateDns(ComponentName who, int mode, String privateDnsHost) {
        return DevicePolicyManager.PRIVATE_DNS_SET_ERROR_FAILURE_SETTING;
    }

    @Override
    public int getGlobalPrivateDnsMode(ComponentName who) {
        return DevicePolicyManager.PRIVATE_DNS_MODE_UNKNOWN;
    }

    @Override
    public String getGlobalPrivateDnsHost(ComponentName who) {
        return null;
    }

    @Override
    public void grantDeviceIdsAccessToProfileOwner(ComponentName who, int userId) { }

    @Override
    public int getPasswordComplexity() {
        return DevicePolicyManager.PASSWORD_COMPLEXITY_NONE;
    }

    @Override
    public void installUpdateFromFile(ComponentName admin,
            ParcelFileDescriptor updateFileDescriptor, StartInstallingUpdateCallback listener) {}

    @Override
    public void setCrossProfileCalendarPackages(ComponentName admin, List<String> packageNames) {
    }

    @Override
    public List<String> getCrossProfileCalendarPackages(ComponentName admin) {
        return Collections.emptyList();
    }

    @Override
    public boolean isPackageAllowedToAccessCalendarForUser(String packageName,
            int userHandle) {
        return false;
    }

    @Override
    public List<String> getCrossProfileCalendarPackagesForUser(int userHandle) {
        return Collections.emptyList();
    }

    @Override
    public boolean isManagedKiosk() {
        return false;
    }

    @Override
    public boolean isUnattendedManagedKiosk() {
        return false;
    }

    @Override
    public boolean startViewCalendarEventInManagedProfile(String packageName, long eventId,
            long start, long end, boolean allDay, int flags) {
        return false;
    }
}
