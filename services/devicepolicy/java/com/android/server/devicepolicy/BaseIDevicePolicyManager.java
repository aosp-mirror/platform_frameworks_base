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

import android.accounts.Account;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.admin.DevicePolicySafetyChecker;
import android.app.admin.FullyManagedDeviceProvisioningParams;
import android.app.admin.IDevicePolicyManager;
import android.app.admin.ManagedProfileProvisioningParams;
import android.content.ComponentName;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.SystemService;

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

    private static final String TAG = BaseIDevicePolicyManager.class.getSimpleName();

    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} during the various boot phases.
     *
     * @see {@link SystemService#onBootPhase}.
     */
    abstract void systemReady(int phase);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a new user starts.
     *
     * @see {@link SystemService#onUserStarting}
     */
    abstract void handleStartUser(int userId);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a user is being unlocked.
     *
     * @see {@link SystemService#onUserUnlocking}
     */
    abstract void handleUnlockUser(int userId);

    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} after a user is being unlocked.
     *
     * @see {@link SystemService#onUserUnlocked}
     */
    abstract void handleOnUserUnlocked(int userId);
    /**
     * To be called by {@link DevicePolicyManagerService#Lifecycle} when a user is being stopped.
     *
     * @see {@link SystemService#onUserStopping}
     */
    abstract void handleStopUser(int userId);

    /**
     * Sets the {@link DevicePolicySafetyChecker}.
     *
     * <p>Currently, it's called only by {@code SystemServer} on
     * {@link android.content.pm.PackageManager#FEATURE_AUTOMOTIVE automotive builds}
     */
    public void setDevicePolicySafetyChecker(DevicePolicySafetyChecker safetyChecker) {
        Slog.w(TAG, "setDevicePolicySafetyChecker() not implemented by " + getClass());
    }

    public void clearSystemUpdatePolicyFreezePeriodRecord() {
    }

    public boolean setKeyGrantForApp(ComponentName admin, String callerPackage, String alias,
            String packageName, boolean hasGrant) {
        return false;
    }

    public void setLocationEnabled(ComponentName who, boolean locationEnabled) {}

    public boolean isOrganizationOwnedDeviceWithManagedProfile() {
        return false;
    }

    public int getPersonalAppsSuspendedReasons(ComponentName admin) {
        return 0;
    }

    public void setPersonalAppsSuspended(ComponentName admin, boolean suspended) {
    }

    public void setManagedProfileMaximumTimeOff(ComponentName admin, long timeoutMs) {
    }

    public long getManagedProfileMaximumTimeOff(ComponentName admin) {
        return 0;
    }

    @Override
    public void acknowledgeDeviceCompliant() {}

    @Override
    public boolean isComplianceAcknowledgementRequired() {
        return false;
    }

    public boolean canProfileOwnerResetPasswordWhenLocked(int userId) {
        return false;
    }

    public String getEnrollmentSpecificId(String callerPackage) {
        return "";
    }

    public void setOrganizationIdForUser(
            @NonNull String callerPackage, @NonNull String enterpriseId, int userId) {}

    public UserHandle createAndProvisionManagedProfile(
            @NonNull ManagedProfileProvisioningParams provisioningParams, String callerPackage) {
        return null;
    }

    public void finalizeWorkProfileProvisioning(
            UserHandle managedProfileUser, Account migratedAccount) {

    }

    public void provisionFullyManagedDevice(
            FullyManagedDeviceProvisioningParams provisioningParams, String callerPackage) {
    }

    @Override
    public void setDeviceOwnerType(@NonNull ComponentName admin, int deviceOwnerType) {
    }

    @Override
    public int getDeviceOwnerType(@NonNull ComponentName admin) {
        return 0;
    }

    public void resetDefaultCrossProfileIntentFilters(@UserIdInt int userId) {}

    public boolean canAdminGrantSensorsPermissionsForUser(int userId) {
        return false;
    }

    @Override
    public boolean setKeyGrantToWifiAuth(String callerPackage, String alias, boolean hasGrant) {
        return false;
    }

    @Override
    public boolean isKeyPairGrantedToWifiAuth(String callerPackage, String alias) {
        return false;
    }
}
