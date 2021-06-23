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

import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.util.JournaledFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

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

    public PolicyVersionUpgrader(PolicyUpgraderDataProvider provider) {
        mProvider = provider;
    }

    /**
     * Performs the upgrade steps for all users on the system.
     *
     * @param allUsers List of all user IDs on the system, including disabled users, as well as
     *                 managed profile user IDs.
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

        //NOTE: The current version is provided in case the XML file format changes in a
        // non-backwards-compatible way, so that DeviceAdminData could load it with
        // old tags, for example.
        final SparseArray<DevicePolicyData> allUsersData = loadAllUsersData(allUsers, oldVersion);

        int currentVersion = oldVersion;
        if (currentVersion == 0) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            // The first upgrade (from no version to version 1) is to overwrite
            // the "active-password" tag in case it was left around.
            currentVersion = 1;
        }

        if (currentVersion == 1) {
            Slog.i(LOG_TAG, String.format("Upgrading from version %d", currentVersion));
            // This upgrade step is for Device Owner scenario only: For devices upgrading to S,
            // if there is a device owner, it retains the ability to control sensors-related
            // permission grants.
            for (int userId : allUsers) {
                DevicePolicyData userData = allUsersData.get(userId);
                if (userData == null) {
                    continue;
                }
                for (ActiveAdmin admin : userData.mAdminList) {
                    if (mProvider.isDeviceOwner(userId, admin.info.getComponent())) {
                        Slog.i(LOG_TAG, String.format(
                                "Marking Device Owner in user %d for permission grant ", userId));
                        admin.mAdminCanGrantSensorsPermissions = true;
                    }
                }
            }
            currentVersion = 2;
        }

        writePoliciesAndVersion(allUsers, allUsersData, currentVersion);
    }

    private void writePoliciesAndVersion(int[] allUsers, SparseArray<DevicePolicyData> allUsersData,
            int currentVersion) {
        boolean allWritesSuccessful = true;
        for (int user : allUsers) {
            allWritesSuccessful = allWritesSuccessful && writeDataForUser(user,
                    allUsersData.get(user));
        }

        if (allWritesSuccessful) {
            writeVersion(currentVersion);
        } else {
            Slog.e(LOG_TAG, String.format("Error: Failed upgrading policies to version %d",
                    currentVersion));
        }
    }

    private SparseArray<DevicePolicyData> loadAllUsersData(int[] allUsers, int loadVersion) {
        final SparseArray<DevicePolicyData> allUsersData = new SparseArray<>();
        for (int user: allUsers) {
            allUsersData.append(user, loadDataForUser(user, loadVersion));
        }
        return allUsersData;
    }

    private DevicePolicyData loadDataForUser(int userId, int loadVersion) {
        DevicePolicyData policy = new DevicePolicyData(userId);
        DevicePolicyData.load(policy,
                !mProvider.storageManagerIsFileBasedEncryptionEnabled(),
                mProvider.makeDevicePoliciesJournaledFile(userId),
                mProvider.getAdminInfoSupplier(userId),
                mProvider.getOwnerComponent(userId));
        return policy;
    }

    private boolean writeDataForUser(int userId, DevicePolicyData policy) {
        return DevicePolicyData.store(
                policy,
                mProvider.makeDevicePoliciesJournaledFile(userId),
                !mProvider.storageManagerIsFileBasedEncryptionEnabled());
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
            Slog.e(LOG_TAG, String.format("Writing version %d failed: %s", version), e);
            versionFile.rollback();
        }
    }
}
