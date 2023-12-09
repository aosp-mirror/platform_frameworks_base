/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.JournaledFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;

/**
 * Class for dealing with Device Policy Manager Service version upgrades.
 * Initially, this class is responsible for upgrading the "device_policies.xml" file upon
 * platform version upgrade.
 *
 * It is useful for policies which have a different default for an upgrading device than a
 * newly-configured device (for example, the admin can grant sensors-related permissions by
 * default on existing fully-managed devices that upgrade to Android S, but on devices set up
 * with Android S the value of the policy is set explicitly during set-up).
 *
 * Practically, it's useful for changes to the data model of the {@code DevicePolicyData} and
 * {@code ActiveAdmin} classes.
 *
 * To add a new upgrade step:
 * (1) Increase the {@code DPMS_VERSION} constant in {@code DevicePolicyManagerService} by one.
 * (2) Add an if statement in {@code upgradePolicy} comparing the version, performing the upgrade
 *     step and setting the value of {@code currentVersion} to the newly-incremented version.
 * (3) Add a test in {@code PolicyVersionUpgraderTest}.
 */
public class PolicyVersionUpgrader {
    private static final String LOG_TAG = "DevicePolicyManager";
    private static final boolean VERBOSE_LOG = DevicePolicyManagerService.VERBOSE_LOG;
    private final PolicyUpgraderDataProvider mProvider;
    private final PolicyPathProvider mPathProvider;

    @VisibleForTesting
    PolicyVersionUpgrader(PolicyUpgraderDataProvider provider, PolicyPathProvider pathProvider) {
        mProvider = provider;
        mPathProvider = pathProvider;
    }
    /**
     * Performs the upgrade steps for all users on the system.
     *
     * @param dpmsVersion The version to upgrade to.
     */
    public void upgradePolicy(int dpmsVersion) {
        int oldVersion = readVersion();
        if (oldVersion >= dpmsVersion) {
            Slog.i(LOG_TAG, String.format("Current version %d, latest version %d, not upgrading.",
                    oldVersion, dpmsVersion));
            return;
        }

        final int[] allUsers = mProvider.getUsersForUpgrade();
        final OwnersData ownersData = loadOwners(allUsers);

        // NOTE: The current version is provided in case the XML file format changes in a
        // non-backwards-compatible way, so that DeviceAdminData could load it with
        // old tags, for example.
        final SparseArray<DevicePolicyData> allUsersData =
                loadAllUsersData(allUsers, oldVersion, ownersData);

        int currentVersion = oldVersion;
        if (currentVersion == 0) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            // The first upgrade (from no version to version 1) is to overwrite
            // the "active-password" tag in case it was left around.
            currentVersion = 1;
        }

        if (currentVersion == 1) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            upgradeSensorPermissionsAccess(allUsers, ownersData, allUsersData);
            currentVersion = 2;
        }

        if (currentVersion == 2) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            upgradeProtectedPackages(ownersData, allUsersData);
            currentVersion = 3;
        }

        if (currentVersion == 3) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            upgradePackageSuspension(allUsers, ownersData, allUsersData);
            currentVersion = 4;
        }

        if (currentVersion == 4) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            initializeEffectiveKeepProfilesRunning(allUsersData);
            currentVersion = 5;
        }

        if (currentVersion == 5) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            // No-op upgrade here:
            // DevicePolicyData.mEffectiveKeepProfilesRunning is only stored in XML file when it is
            // different from its default value, otherwise the tag is not written. When loading, if
            // the tag is missing, the field retains the value previously assigned in the
            // constructor, which is the default value.
            // In version 5 the default value was 'true', in version 6 it is 'false', so when
            // loading XML version 5 we need to initialize the field to 'true' for it to be restored
            // correctly in case the tag is missing. This is done in loadDataForUser().
            currentVersion = 6;
        }

        writePoliciesAndVersion(allUsers, allUsersData, ownersData, currentVersion);
    }

    /**
     * This upgrade step is for Device Owner scenario only: For devices upgrading to S, if there is
     * a device owner, it retains the ability to control sensors-related permission grants.
     */
    private void upgradeSensorPermissionsAccess(
            int[] allUsers, OwnersData ownersData, SparseArray<DevicePolicyData> allUsersData) {
        for (int userId : allUsers) {
            DevicePolicyData userData = allUsersData.get(userId);
            if (userData == null) {
                continue;
            }
            for (ActiveAdmin admin : userData.mAdminList) {
                if (ownersData.mDeviceOwnerUserId == userId
                        && ownersData.mDeviceOwner != null
                        && ownersData.mDeviceOwner.admin.equals(admin.info.getComponent())) {
                    Slog.i(LOG_TAG, String.format(
                            "Marking Device Owner in user %d for permission grant ", userId));
                    admin.mAdminCanGrantSensorsPermissions = true;
                }
            }
        }
    }

    /**
     * This upgrade step moves device owner protected packages to ActiveAdmin.
     * Initially these packages were stored in DevicePolicyData, then moved to Owners without
     * employing PolicyVersionUpgrader. Here we check both places.
     */
    private void upgradeProtectedPackages(
            OwnersData ownersData, SparseArray<DevicePolicyData> allUsersData) {
        if (ownersData.mDeviceOwner == null) {
            return;
        }
        List<String> protectedPackages = null;
        DevicePolicyData doUserData = allUsersData.get(ownersData.mDeviceOwnerUserId);
        if (doUserData == null) {
            Slog.e(LOG_TAG, "No policy data for do user");
            return;
        }
        if (ownersData.mDeviceOwnerProtectedPackages != null) {
            protectedPackages = ownersData.mDeviceOwnerProtectedPackages
                    .get(ownersData.mDeviceOwner.packageName);
            if (protectedPackages != null) {
                Slog.i(LOG_TAG, "Found protected packages in Owners");
            }
            ownersData.mDeviceOwnerProtectedPackages = null;
        } else if (doUserData.mUserControlDisabledPackages != null) {
            Slog.i(LOG_TAG, "Found protected packages in DevicePolicyData");
            protectedPackages = doUserData.mUserControlDisabledPackages;
            doUserData.mUserControlDisabledPackages = null;
        }

        ActiveAdmin doAdmin = doUserData.mAdminMap.get(ownersData.mDeviceOwner.admin);
        if (doAdmin == null) {
            Slog.e(LOG_TAG, "DO admin not found in DO user");
            return;
        }

        if (protectedPackages != null) {
            doAdmin.protectedPackages = new ArrayList<>(protectedPackages);
        }
    }

    /**
     * This upgrade step stores packages suspended via DPM.setPackagesSuspended() into ActiveAdmin
     * data structure. Prior to this it was only persisted in PackageManager which doesn't have any
     * way of knowing which admin suspended it.
     */
    private void upgradePackageSuspension(
            int[] allUsers, OwnersData ownersData, SparseArray<DevicePolicyData> allUsersData) {
        if (ownersData.mDeviceOwner != null) {
            saveSuspendedPackages(allUsersData, ownersData.mDeviceOwnerUserId,
                    ownersData.mDeviceOwner.admin);
        }

        for (int i = 0; i < ownersData.mProfileOwners.size(); i++) {
            int ownerUserId = ownersData.mProfileOwners.keyAt(i);
            OwnersData.OwnerInfo ownerInfo = ownersData.mProfileOwners.valueAt(i);
            saveSuspendedPackages(allUsersData, ownerUserId, ownerInfo.admin);
        }
    }

    private void saveSuspendedPackages(SparseArray<DevicePolicyData> allUsersData, int ownerUserId,
            ComponentName ownerPackage) {
        DevicePolicyData ownerUserData = allUsersData.get(ownerUserId);
        if (ownerUserData == null) {
            Slog.e(LOG_TAG, "No policy data for owner user, cannot migrate suspended packages");
            return;
        }

        ActiveAdmin ownerAdmin = ownerUserData.mAdminMap.get(ownerPackage);
        if (ownerAdmin == null) {
            Slog.e(LOG_TAG, "No admin for owner, cannot migrate suspended packages");
            return;
        }

        ownerAdmin.suspendedPackages = mProvider.getPlatformSuspendedPackages(ownerUserId);
        Slog.i(LOG_TAG, String.format("Saved %d packages suspended by %s in user %d",
                ownerAdmin.suspendedPackages.size(), ownerPackage, ownerUserId));
    }

    private void initializeEffectiveKeepProfilesRunning(
            SparseArray<DevicePolicyData> allUsersData) {
        DevicePolicyData systemUserData = allUsersData.get(UserHandle.USER_SYSTEM);
        if (systemUserData == null) {
            return;
        }
        systemUserData.mEffectiveKeepProfilesRunning = false;
        Slog.i(LOG_TAG, "Keep profile running effective state set to false");
    }

    private OwnersData loadOwners(int[] allUsers) {
        OwnersData ownersData = new OwnersData(mPathProvider);
        ownersData.load(allUsers);
        return ownersData;
    }

    private void writePoliciesAndVersion(int[] allUsers, SparseArray<DevicePolicyData> allUsersData,
            OwnersData ownersData, int currentVersion) {
        boolean allWritesSuccessful = true;
        for (int user : allUsers) {
            allWritesSuccessful =
                    allWritesSuccessful && writeDataForUser(user, allUsersData.get(user));
        }

        allWritesSuccessful = allWritesSuccessful && ownersData.writeDeviceOwner();
        for (int user : allUsers) {
            allWritesSuccessful = allWritesSuccessful && ownersData.writeProfileOwner(user);
        }

        if (allWritesSuccessful) {
            writeVersion(currentVersion);
        } else {
            Slog.e(LOG_TAG, String.format("Error: Failed upgrading policies to version %d",
                    currentVersion));
        }
    }

    private SparseArray<DevicePolicyData> loadAllUsersData(int[] allUsers, int loadVersion,
            OwnersData ownersData) {
        final SparseArray<DevicePolicyData> allUsersData = new SparseArray<>();
        for (int user: allUsers) {
            ComponentName owner = getOwnerForUser(ownersData, user);
            allUsersData.append(user, loadDataForUser(user, loadVersion, owner));
        }
        return allUsersData;
    }

    @Nullable
    private ComponentName getOwnerForUser(OwnersData ownersData, int user) {
        ComponentName owner = null;
        if (ownersData.mDeviceOwnerUserId == user && ownersData.mDeviceOwner != null) {
            owner = ownersData.mDeviceOwner.admin;
        } else if (ownersData.mProfileOwners.containsKey(user)) {
            owner = ownersData.mProfileOwners.get(user).admin;
        }
        return owner;
    }

    private DevicePolicyData loadDataForUser(
            int userId, int loadVersion, ComponentName ownerComponent) {
        DevicePolicyData policy = new DevicePolicyData(userId);
        // See version 5 -> 6 step in upgradePolicy()
        if (loadVersion == 5 && userId == UserHandle.USER_SYSTEM) {
            policy.mEffectiveKeepProfilesRunning = true;
        }
        DevicePolicyData.load(policy,
                mProvider.makeDevicePoliciesJournaledFile(userId),
                mProvider.getAdminInfoSupplier(userId),
                ownerComponent);
        return policy;
    }

    private boolean writeDataForUser(int userId, DevicePolicyData policy) {
        return DevicePolicyData.store(policy, mProvider.makeDevicePoliciesJournaledFile(userId));
    }

    private JournaledFile getVersionFile() {
        return mProvider.makePoliciesVersionJournaledFile(UserHandle.USER_SYSTEM);
    }

    private int readVersion() {
        JournaledFile versionFile = getVersionFile();

        File file = versionFile.chooseForRead();
        if (VERBOSE_LOG) {
            Slog.v(LOG_TAG, "Loading version from " + file);
        }
        try {
            String versionString = Files.readAllLines(
                    file.toPath(), Charset.defaultCharset()).get(0);
            return Integer.parseInt(versionString);
        } catch (NoSuchFileException e) {
            return 0; // expected on first boot
        } catch (IOException | NumberFormatException | IndexOutOfBoundsException e) {
            Slog.e(LOG_TAG, "Error reading version", e);
            return 0;
        }
    }

    private void writeVersion(int version) {
        JournaledFile versionFile = getVersionFile();

        File file = versionFile.chooseForWrite();
        if (VERBOSE_LOG) {
            Slog.v(LOG_TAG, String.format("Writing new version to: %s", file));
        }

        try {
            byte[] versionBytes = String.format("%d", version).getBytes();
            Files.write(file.toPath(), versionBytes);
            versionFile.commit();
        } catch (IOException e) {
            Slog.e(LOG_TAG, String.format("Writing version %d failed", version), e);
            versionFile.rollback();
        }
    }
}
