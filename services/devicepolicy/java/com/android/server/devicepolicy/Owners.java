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

package com.android.server.devicepolicy;

import static android.app.admin.DevicePolicyManager.DEVICE_OWNER_TYPE_DEFAULT;

import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManagerInternal;
import android.app.admin.DevicePolicyManager.DeviceOwnerType;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.devicepolicy.OwnersData.OwnerInfo;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Stores and restores state for the Device and Profile owners and related device-wide information.
 * By definition there can be only one device owner, but there may be a profile owner for each user.
 *
 * <p>This class is thread safe, so individual methods can safely be called without locking.
 * However, caller must still synchronize on their side to ensure integrity between multiple calls.
 */
class Owners {
    private static final String TAG = "DevicePolicyManagerService";

    private static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE

    private final UserManager mUserManager;
    private final UserManagerInternal mUserManagerInternal;
    private final PackageManagerInternal mPackageManagerInternal;
    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;

    @GuardedBy("mData")
    private final OwnersData mData;

    private boolean mSystemReady;

    @VisibleForTesting
    Owners(UserManager userManager,
            UserManagerInternal userManagerInternal,
            PackageManagerInternal packageManagerInternal,
            ActivityTaskManagerInternal activityTaskManagerInternal,
            ActivityManagerInternal activityManagerInternal,
            PolicyPathProvider pathProvider) {
        mUserManager = userManager;
        mUserManagerInternal = userManagerInternal;
        mPackageManagerInternal = packageManagerInternal;
        mActivityTaskManagerInternal = activityTaskManagerInternal;
        mActivityManagerInternal = activityManagerInternal;
        mData = new OwnersData(pathProvider);
    }

    /**
     * Load configuration from the disk.
     */
    void load() {
        synchronized (mData) {
            int[] usersIds =
                    mUserManager.getAliveUsers().stream().mapToInt(u -> u.id).toArray();
            mData.load(usersIds);

            mUserManagerInternal.setDeviceManaged(hasDeviceOwner());
            for (int userId : usersIds) {
                mUserManagerInternal.setUserManaged(userId, hasProfileOwner(userId));
            }

            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    // Notify interested parties that things have changed. This does not notify the
    // ActivityTaskManager.
    @GuardedBy("mData")
    private void notifyChangeLocked() {
        pushToDevicePolicyManager();
        pushToPackageManagerLocked();
        pushToActivityManagerLocked();
        pushToAppOpsLocked();
    }

    private void pushToDevicePolicyManager() {
        // Not every change here must invalidate the DPM caches, but there is no harm in
        // invalidating the caches unnecessarily, provided the invalidation is infrequent.
        DevicePolicyManagerService.invalidateBinderCaches();
    }

    @GuardedBy("mData")
    private void pushToPackageManagerLocked() {
        final SparseArray<String> po = new SparseArray<>();
        for (int i = mData.mProfileOwners.size() - 1; i >= 0; i--) {
            po.put(mData.mProfileOwners.keyAt(i), mData.mProfileOwners.valueAt(i).packageName);
        }
        final String doPackage = mData.mDeviceOwner != null ? mData.mDeviceOwner.packageName : null;
        mPackageManagerInternal.setDeviceAndProfileOwnerPackages(
                mData.mDeviceOwnerUserId, doPackage, po);
    }

    @GuardedBy("mData")
    private void pushToActivityTaskManagerLocked() {
        mActivityTaskManagerInternal.setDeviceOwnerUid(getDeviceOwnerUidLocked());
    }

    @GuardedBy("mData")
    private void pushToActivityManagerLocked() {
        mActivityManagerInternal.setDeviceOwnerUid(getDeviceOwnerUidLocked());

        final ArraySet<Integer> profileOwners = new ArraySet<>();
        for (int poi = mData.mProfileOwners.size() - 1; poi >= 0; poi--) {
            final int userId = mData.mProfileOwners.keyAt(poi);
            final int profileOwnerUid = mPackageManagerInternal.getPackageUid(
                    mData.mProfileOwners.valueAt(poi).packageName,
                    PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                    userId);
            if (profileOwnerUid >= 0) {
                profileOwners.add(profileOwnerUid);
            }
        }
        mActivityManagerInternal.setProfileOwnerUid(profileOwners);
    }

    @GuardedBy("mData")
    int getDeviceOwnerUidLocked() {
        if (mData.mDeviceOwner != null) {
            return mPackageManagerInternal.getPackageUid(mData.mDeviceOwner.packageName,
                    PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                    mData.mDeviceOwnerUserId);
        } else {
            return Process.INVALID_UID;
        }
    }

    String getDeviceOwnerPackageName() {
        synchronized (mData) {
            return mData.mDeviceOwner != null ? mData.mDeviceOwner.packageName : null;
        }
    }

    int getDeviceOwnerUserId() {
        synchronized (mData) {
            return mData.mDeviceOwnerUserId;
        }
    }

    @Nullable
    Pair<Integer, ComponentName> getDeviceOwnerUserIdAndComponent() {
        synchronized (mData) {
            if (mData.mDeviceOwner == null) {
                return null;
            } else {
                return Pair.create(mData.mDeviceOwnerUserId, mData.mDeviceOwner.admin);
            }
        }
    }

    String getDeviceOwnerName() {
        synchronized (mData) {
            return mData.mDeviceOwner != null ? mData.mDeviceOwner.name : null;
        }
    }

    ComponentName getDeviceOwnerComponent() {
        synchronized (mData) {
            return mData.mDeviceOwner != null ? mData.mDeviceOwner.admin : null;
        }
    }

    String getDeviceOwnerRemoteBugreportUri() {
        synchronized (mData) {
            return mData.mDeviceOwner != null ? mData.mDeviceOwner.remoteBugreportUri : null;
        }
    }

    String getDeviceOwnerRemoteBugreportHash() {
        synchronized (mData) {
            return mData.mDeviceOwner != null ? mData.mDeviceOwner.remoteBugreportHash : null;
        }
    }

    void setDeviceOwner(ComponentName admin, String ownerName, int userId) {
        if (userId < 0) {
            Slog.e(TAG, "Invalid user id for device owner user: " + userId);
            return;
        }
        synchronized (mData) {
            // A device owner is allowed to access device identifiers. Even though this flag
            // is not currently checked for device owner, it is set to true here so that it is
            // semantically compatible with the meaning of this flag.
            mData.mDeviceOwner = new OwnerInfo(ownerName, admin, /* remoteBugreportUri =*/ null,
                    /* remoteBugreportHash =*/ null, /* isOrganizationOwnedDevice =*/ true);
            mData.mDeviceOwnerUserId = userId;

            mUserManagerInternal.setDeviceManaged(true);
            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    void clearDeviceOwner() {
        synchronized (mData) {
            mData.mDeviceOwnerTypes.remove(mData.mDeviceOwner.packageName);
            mData.mDeviceOwner = null;
            mData.mDeviceOwnerUserId = UserHandle.USER_NULL;

            mUserManagerInternal.setDeviceManaged(false);
            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    void setProfileOwner(ComponentName admin, String ownerName, int userId) {
        synchronized (mData) {
            // For a newly set PO, there's no need for migration.
            mData.mProfileOwners.put(userId, new OwnerInfo(ownerName, admin,
                    /* remoteBugreportUri =*/ null, /* remoteBugreportHash =*/ null,
                    /* isOrganizationOwnedDevice =*/ false));
            mUserManagerInternal.setUserManaged(userId, true);
            notifyChangeLocked();
        }
    }

    void removeProfileOwner(int userId) {
        synchronized (mData) {
            mData.mProfileOwners.remove(userId);
            mUserManagerInternal.setUserManaged(userId, false);
            notifyChangeLocked();
        }
    }

    void transferProfileOwner(ComponentName target, int userId) {
        synchronized (mData) {
            final OwnerInfo ownerInfo = mData.mProfileOwners.get(userId);
            final OwnerInfo newOwnerInfo = new OwnerInfo(target.getPackageName(), target,
                    ownerInfo.remoteBugreportUri, ownerInfo.remoteBugreportHash,
                    ownerInfo.isOrganizationOwnedDevice);
            mData.mProfileOwners.put(userId, newOwnerInfo);
            notifyChangeLocked();
        }
    }

    void transferDeviceOwnership(ComponentName target) {
        synchronized (mData) {
            Integer previousDeviceOwnerType = mData.mDeviceOwnerTypes.remove(
                    mData.mDeviceOwner.packageName);
            // We don't set a name because it's not used anyway.
            // See DevicePolicyManagerService#getDeviceOwnerName
            mData.mDeviceOwner = new OwnerInfo(null, target,
                    mData.mDeviceOwner.remoteBugreportUri,
                    mData.mDeviceOwner.remoteBugreportHash,
                    mData.mDeviceOwner.isOrganizationOwnedDevice);

            if (previousDeviceOwnerType != null) {
                mData.mDeviceOwnerTypes.put(
                        mData.mDeviceOwner.packageName, previousDeviceOwnerType);
            }
            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    ComponentName getProfileOwnerComponent(int userId) {
        synchronized (mData) {
            OwnerInfo profileOwner = mData.mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.admin : null;
        }
    }

    String getProfileOwnerName(int userId) {
        synchronized (mData) {
            OwnerInfo profileOwner = mData.mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.name : null;
        }
    }

    String getProfileOwnerPackage(int userId) {
        synchronized (mData) {
            OwnerInfo profileOwner = mData.mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.packageName : null;
        }
    }

    /**
     * Returns true if {@code userId} has a profile owner and that profile owner is on an
     * organization-owned device, as indicated by the provisioning flow.
     */
    boolean isProfileOwnerOfOrganizationOwnedDevice(int userId) {
        synchronized (mData) {
            OwnerInfo profileOwner = mData.mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.isOrganizationOwnedDevice : false;
        }
    }

    Set<Integer> getProfileOwnerKeys() {
        synchronized (mData) {
            return mData.mProfileOwners.keySet();
        }
    }

    List<OwnerShellData> listAllOwners() {
        List<OwnerShellData> owners = new ArrayList<>();
        synchronized (mData) {
            if (mData.mDeviceOwner != null) {
                owners.add(OwnerShellData.forDeviceOwner(mData.mDeviceOwnerUserId,
                        mData.mDeviceOwner.admin));
            }
            for (int i = 0; i < mData.mProfileOwners.size(); i++) {
                int userId = mData.mProfileOwners.keyAt(i);
                OwnerInfo info = mData.mProfileOwners.valueAt(i);
                owners.add(OwnerShellData.forUserProfileOwner(userId, info.admin));
            }
        }
        return owners;
    }


    SystemUpdatePolicy getSystemUpdatePolicy() {
        synchronized (mData) {
            return mData.mSystemUpdatePolicy;
        }
    }

    void setSystemUpdatePolicy(SystemUpdatePolicy systemUpdatePolicy) {
        synchronized (mData) {
            mData.mSystemUpdatePolicy = systemUpdatePolicy;
        }
    }

    void clearSystemUpdatePolicy() {
        synchronized (mData) {
            mData.mSystemUpdatePolicy = null;
        }
    }

    Pair<LocalDate, LocalDate> getSystemUpdateFreezePeriodRecord() {
        synchronized (mData) {
            return new Pair<>(mData.mSystemUpdateFreezeStart,
                    mData.mSystemUpdateFreezeEnd);
        }
    }

    String getSystemUpdateFreezePeriodRecordAsString() {
        synchronized (mData) {
            return mData.getSystemUpdateFreezePeriodRecordAsString();
        }
    }

    /**
     * Returns {@code true} if the freeze period record is changed, {@code false} otherwise.
     */
    boolean setSystemUpdateFreezePeriodRecord(LocalDate start, LocalDate end) {
        boolean changed = false;
        synchronized (mData) {
            if (!Objects.equals(mData.mSystemUpdateFreezeStart, start)) {
                mData.mSystemUpdateFreezeStart = start;
                changed = true;
            }
            if (!Objects.equals(mData.mSystemUpdateFreezeEnd, end)) {
                mData.mSystemUpdateFreezeEnd = end;
                changed = true;
            }
        }
        return changed;
    }

    boolean hasDeviceOwner() {
        synchronized (mData) {
            return mData.mDeviceOwner != null;
        }
    }

    boolean isDeviceOwnerUserId(int userId) {
        synchronized (mData) {
            return mData.mDeviceOwner != null && mData.mDeviceOwnerUserId == userId;
        }
    }

    boolean hasProfileOwner(int userId) {
        synchronized (mData) {
            return getProfileOwnerComponent(userId) != null;
        }
    }

    /** Sets the remote bugreport uri and hash, and also writes to the file. */
    void setDeviceOwnerRemoteBugreportUriAndHash(String remoteBugreportUri,
            String remoteBugreportHash) {
        synchronized (mData) {
            if (mData.mDeviceOwner != null) {
                mData.mDeviceOwner.remoteBugreportUri = remoteBugreportUri;
                mData.mDeviceOwner.remoteBugreportHash = remoteBugreportHash;
            }
            writeDeviceOwner();
        }
    }

    /** Set whether the profile owner manages an organization-owned device, then write to file. */
    void setProfileOwnerOfOrganizationOwnedDevice(int userId, boolean isOrganizationOwnedDevice) {
        synchronized (mData) {
            OwnerInfo profileOwner = mData.mProfileOwners.get(userId);
            if (profileOwner != null) {
                profileOwner.isOrganizationOwnedDevice = isOrganizationOwnedDevice;
            } else {
                Slog.e(TAG, String.format(
                        "No profile owner for user %d to set org-owned flag.", userId));
            }
            writeProfileOwner(userId);
        }
    }

    void setDeviceOwnerType(String packageName, @DeviceOwnerType int deviceOwnerType,
            boolean isAdminTestOnly) {
        synchronized (mData) {
            if (!hasDeviceOwner()) {
                Slog.e(TAG, "Attempting to set a device owner type when there is no device owner");
                return;
            } else if (!isAdminTestOnly && isDeviceOwnerTypeSetForDeviceOwner(packageName)) {
                Slog.e(TAG, "Setting the device owner type more than once is only allowed"
                        + " for test only admins");
                return;
            }

            mData.mDeviceOwnerTypes.put(packageName, deviceOwnerType);
            writeDeviceOwner();
        }
    }

    @DeviceOwnerType
    int getDeviceOwnerType(String packageName) {
        synchronized (mData) {
            if (isDeviceOwnerTypeSetForDeviceOwner(packageName)) {
                return mData.mDeviceOwnerTypes.get(packageName);
            }
            return DEVICE_OWNER_TYPE_DEFAULT;
        }
    }

    boolean isDeviceOwnerTypeSetForDeviceOwner(String packageName) {
        synchronized (mData) {
            return !mData.mDeviceOwnerTypes.isEmpty()
                    && mData.mDeviceOwnerTypes.containsKey(packageName);
        }
    }

    void writeDeviceOwner() {
        synchronized (mData) {
            pushToDevicePolicyManager();
            mData.writeDeviceOwner();
        }
    }

    void writeProfileOwner(int userId) {
        synchronized (mData) {
            pushToDevicePolicyManager();
            mData.writeProfileOwner(userId);
        }
    }

    /**
     * Saves the given {@link SystemUpdateInfo} if it is different from the existing one, or if
     * none exists.
     *
     * @return Whether the saved system update information has changed.
     */
    boolean saveSystemUpdateInfo(@Nullable SystemUpdateInfo newInfo) {
        synchronized (mData) {
            // Check if we already have the same update information.
            if (Objects.equals(newInfo, mData.mSystemUpdateInfo)) {
                return false;
            }

            mData.mSystemUpdateInfo = newInfo;
            mData.writeDeviceOwner();
            return true;
        }
    }

    @Nullable
    public SystemUpdateInfo getSystemUpdateInfo() {
        synchronized (mData) {
            return mData.mSystemUpdateInfo;
        }
    }

    @GuardedBy("mData")
    void pushToAppOpsLocked() {
        if (!mSystemReady) {
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            final SparseIntArray owners = new SparseIntArray();
            if (mData.mDeviceOwner != null) {
                final int uid = getDeviceOwnerUidLocked();
                if (uid >= 0) {
                    owners.put(mData.mDeviceOwnerUserId, uid);
                }
            }
            if (mData.mProfileOwners != null) {
                for (int poi = mData.mProfileOwners.size() - 1; poi >= 0; poi--) {
                    final int uid = mPackageManagerInternal.getPackageUid(
                            mData.mProfileOwners.valueAt(poi).packageName,
                            PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                            mData.mProfileOwners.keyAt(poi));
                    if (uid >= 0) {
                        owners.put(mData.mProfileOwners.keyAt(poi), uid);
                    }
                }
            }
            AppOpsManagerInternal appops = LocalServices.getService(AppOpsManagerInternal.class);
            if (appops != null) {
                appops.setDeviceAndProfileOwners(owners.size() > 0 ? owners : null);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void systemReady() {
        synchronized (mData) {
            mSystemReady = true;
            pushToActivityManagerLocked();
            pushToAppOpsLocked();
        }
    }

    public void dump(IndentingPrintWriter pw) {
        synchronized (mData) {
            mData.dump(pw);
        }
    }

    @VisibleForTesting
    File getDeviceOwnerFile() {
        return mData.getDeviceOwnerFile();
    }

    @VisibleForTesting
    File getProfileOwnerFile(int userId) {
        return mData.getProfileOwnerFile(userId);
    }
}
