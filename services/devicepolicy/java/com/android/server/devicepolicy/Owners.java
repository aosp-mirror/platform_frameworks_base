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
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    // XML storing device owner info, system update policy and pending OTA update information.
    private static final String DEVICE_OWNER_XML = "device_owner_2.xml";

    private static final String PROFILE_OWNER_XML = "profile_owner.xml";

    private static final String TAG_ROOT = "root";

    private static final String TAG_DEVICE_OWNER = "device-owner";
    private static final String TAG_SYSTEM_UPDATE_POLICY = "system-update-policy";
    private static final String TAG_FREEZE_PERIOD_RECORD = "freeze-record";
    private static final String TAG_PENDING_OTA_INFO = "pending-ota-info";
    private static final String TAG_PROFILE_OWNER = "profile-owner";
    // Holds "context" for device-owner, this must not be show up before device-owner.
    private static final String TAG_DEVICE_OWNER_CONTEXT = "device-owner-context";
    private static final String TAG_DEVICE_OWNER_TYPE = "device-owner-type";
    private static final String TAG_DEVICE_OWNER_PROTECTED_PACKAGES =
            "device-owner-protected-packages";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_COMPONENT_NAME = "component";
    private static final String ATTR_SIZE = "size";
    private static final String ATTR_REMOTE_BUGREPORT_URI = "remoteBugreportUri";
    private static final String ATTR_REMOTE_BUGREPORT_HASH = "remoteBugreportHash";
    private static final String ATTR_USERID = "userId";
    private static final String ATTR_FREEZE_RECORD_START = "start";
    private static final String ATTR_FREEZE_RECORD_END = "end";
    // Legacy attribute, its presence would mean the profile owner associated with it is
    // managing a profile on an organization-owned device.
    private static final String ATTR_CAN_ACCESS_DEVICE_IDS = "canAccessDeviceIds";
    // New attribute for profile owner of organization-owned device.
    private static final String ATTR_PROFILE_OWNER_OF_ORG_OWNED_DEVICE =
            "isPoOrganizationOwnedDevice";
    private static final String ATTR_DEVICE_OWNER_TYPE_VALUE = "value";

    private final UserManager mUserManager;
    private final UserManagerInternal mUserManagerInternal;
    private final PackageManagerInternal mPackageManagerInternal;
    private final ActivityTaskManagerInternal mActivityTaskManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;

    private boolean mSystemReady;

    // Internal state for the device owner package.
    private OwnerInfo mDeviceOwner;

    // Device owner type for a managed device.
    private final ArrayMap<String, Integer> mDeviceOwnerTypes = new ArrayMap<>();

    private final ArrayMap<String, List<String>> mDeviceOwnerProtectedPackages = new ArrayMap<>();

    private int mDeviceOwnerUserId = UserHandle.USER_NULL;

    // Internal state for the profile owner packages.
    private final ArrayMap<Integer, OwnerInfo> mProfileOwners = new ArrayMap<>();

    // Local system update policy controllable by device owner.
    private SystemUpdatePolicy mSystemUpdatePolicy;
    private LocalDate mSystemUpdateFreezeStart;
    private LocalDate mSystemUpdateFreezeEnd;

    // Pending OTA info if there is one.
    @Nullable
    private SystemUpdateInfo mSystemUpdateInfo;

    private final Object mLock = new Object();
    private final Injector mInjector;

    public Owners(UserManager userManager,
            UserManagerInternal userManagerInternal,
            PackageManagerInternal packageManagerInternal,
            ActivityTaskManagerInternal activityTaskManagerInternal,
            ActivityManagerInternal activitykManagerInternal) {
        this(userManager, userManagerInternal, packageManagerInternal,
                activityTaskManagerInternal, activitykManagerInternal, new Injector());
    }

    @VisibleForTesting
    Owners(UserManager userManager,
            UserManagerInternal userManagerInternal,
            PackageManagerInternal packageManagerInternal,
            ActivityTaskManagerInternal activityTaskManagerInternal,
            ActivityManagerInternal activityManagerInternal,
            Injector injector) {
        mUserManager = userManager;
        mUserManagerInternal = userManagerInternal;
        mPackageManagerInternal = packageManagerInternal;
        mActivityTaskManagerInternal = activityTaskManagerInternal;
        mActivityManagerInternal = activityManagerInternal;
        mInjector = injector;
    }

    /**
     * Load configuration from the disk.
     */
    void load() {
        synchronized (mLock) {
            final List<UserInfo> users = mUserManager.getAliveUsers();

            new DeviceOwnerReadWriter().readFromFileLocked();

            for (UserInfo ui : users) {
                new ProfileOwnerReadWriter(ui.id).readFromFileLocked();
            }

            mUserManagerInternal.setDeviceManaged(hasDeviceOwner());
            for (UserInfo ui : users) {
                mUserManagerInternal.setUserManaged(ui.id, hasProfileOwner(ui.id));
            }
            if (hasDeviceOwner() && hasProfileOwner(getDeviceOwnerUserId())) {
                Slog.w(TAG, String.format("User %d has both DO and PO, which is not supported",
                        getDeviceOwnerUserId()));
            }

            notifyChangeLocked();
            pushToActivityTaskManagerLocked();

            for (ArrayMap.Entry<String, List<String>> entry :
                    mDeviceOwnerProtectedPackages.entrySet()) {
                mPackageManagerInternal.setDeviceOwnerProtectedPackages(
                        entry.getKey(), entry.getValue());
            }
        }
    }

    // Notify interested parties that things have changed. This does not notify the
    // ActivityTaskManager.
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

    private void pushToPackageManagerLocked() {
        final SparseArray<String> po = new SparseArray<>();
        for (int i = mProfileOwners.size() - 1; i >= 0; i--) {
            po.put(mProfileOwners.keyAt(i), mProfileOwners.valueAt(i).packageName);
        }
        mPackageManagerInternal.setDeviceAndProfileOwnerPackages(
                mDeviceOwnerUserId, (mDeviceOwner != null ? mDeviceOwner.packageName : null),
                po);
    }

    private void pushToActivityTaskManagerLocked() {
        mActivityTaskManagerInternal.setDeviceOwnerUid(getDeviceOwnerUidLocked());
    }

    private void pushToActivityManagerLocked() {
        mActivityManagerInternal.setDeviceOwnerUid(getDeviceOwnerUidLocked());

        final ArraySet<Integer> profileOwners = new ArraySet<>();
        for (int poi = mProfileOwners.size() - 1; poi >= 0; poi--) {
            final int userId = mProfileOwners.keyAt(poi);
            final int profileOwnerUid = mPackageManagerInternal.getPackageUid(
                    mProfileOwners.valueAt(poi).packageName,
                    PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                    userId);
            if (profileOwnerUid >= 0) {
                profileOwners.add(profileOwnerUid);
            }
        }
        mActivityManagerInternal.setProfileOwnerUid(profileOwners);
    }

    int getDeviceOwnerUidLocked() {
        if (mDeviceOwner != null) {
            return mPackageManagerInternal.getPackageUid(mDeviceOwner.packageName,
                    PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                    mDeviceOwnerUserId);
        } else {
            return Process.INVALID_UID;
        }
    }

    String getDeviceOwnerPackageName() {
        synchronized (mLock) {
            return mDeviceOwner != null ? mDeviceOwner.packageName : null;
        }
    }

    int getDeviceOwnerUserId() {
        synchronized (mLock) {
            return mDeviceOwnerUserId;
        }
    }

    @Nullable
    Pair<Integer, ComponentName> getDeviceOwnerUserIdAndComponent() {
        synchronized (mLock) {
            if (mDeviceOwner == null) {
                return null;
            } else {
                return Pair.create(mDeviceOwnerUserId, mDeviceOwner.admin);
            }
        }
    }

    String getDeviceOwnerName() {
        synchronized (mLock) {
            return mDeviceOwner != null ? mDeviceOwner.name : null;
        }
    }

    ComponentName getDeviceOwnerComponent() {
        synchronized (mLock) {
            return mDeviceOwner != null ? mDeviceOwner.admin : null;
        }
    }

    String getDeviceOwnerRemoteBugreportUri() {
        synchronized (mLock) {
            return mDeviceOwner != null ? mDeviceOwner.remoteBugreportUri : null;
        }
    }

    String getDeviceOwnerRemoteBugreportHash() {
        synchronized (mLock) {
            return mDeviceOwner != null ? mDeviceOwner.remoteBugreportHash : null;
        }
    }

    void setDeviceOwner(ComponentName admin, String ownerName, int userId) {
        if (userId < 0) {
            Slog.e(TAG, "Invalid user id for device owner user: " + userId);
            return;
        }
        synchronized (mLock) {
            // A device owner is allowed to access device identifiers. Even though this flag
            // is not currently checked for device owner, it is set to true here so that it is
            // semantically compatible with the meaning of this flag.
            mDeviceOwner = new OwnerInfo(ownerName, admin, /* remoteBugreportUri =*/ null,
                    /* remoteBugreportHash =*/ null, /* isOrganizationOwnedDevice =*/ true);
            mDeviceOwnerUserId = userId;

            mUserManagerInternal.setDeviceManaged(true);
            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    void clearDeviceOwner() {
        synchronized (mLock) {
            mDeviceOwnerTypes.remove(mDeviceOwner.packageName);
            List<String> protectedPackages =
                    mDeviceOwnerProtectedPackages.remove(mDeviceOwner.packageName);
            if (protectedPackages != null) {
                mPackageManagerInternal.setDeviceOwnerProtectedPackages(
                        mDeviceOwner.packageName, new ArrayList<>());
            }
            mDeviceOwner = null;
            mDeviceOwnerUserId = UserHandle.USER_NULL;

            mUserManagerInternal.setDeviceManaged(false);
            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    void setProfileOwner(ComponentName admin, String ownerName, int userId) {
        synchronized (mLock) {
            // For a newly set PO, there's no need for migration.
            mProfileOwners.put(userId, new OwnerInfo(ownerName, admin,
                    /* remoteBugreportUri =*/ null, /* remoteBugreportHash =*/ null,
                    /* isOrganizationOwnedDevice =*/ false));
            mUserManagerInternal.setUserManaged(userId, true);
            notifyChangeLocked();
        }
    }

    void removeProfileOwner(int userId) {
        synchronized (mLock) {
            mProfileOwners.remove(userId);
            mUserManagerInternal.setUserManaged(userId, false);
            notifyChangeLocked();
        }
    }

    void transferProfileOwner(ComponentName target, int userId) {
        synchronized (mLock) {
            final OwnerInfo ownerInfo = mProfileOwners.get(userId);
            final OwnerInfo newOwnerInfo = new OwnerInfo(target.getPackageName(), target,
                    ownerInfo.remoteBugreportUri, ownerInfo.remoteBugreportHash,
                    ownerInfo.isOrganizationOwnedDevice);
            mProfileOwners.put(userId, newOwnerInfo);
            notifyChangeLocked();
        }
    }

    void transferDeviceOwnership(ComponentName target) {
        synchronized (mLock) {
            Integer previousDeviceOwnerType = mDeviceOwnerTypes.remove(mDeviceOwner.packageName);
            List<String> previousProtectedPackages =
                    mDeviceOwnerProtectedPackages.remove(mDeviceOwner.packageName);
            if (previousProtectedPackages != null) {
                mPackageManagerInternal.setDeviceOwnerProtectedPackages(
                        mDeviceOwner.packageName, new ArrayList<>());
            }
            // We don't set a name because it's not used anyway.
            // See DevicePolicyManagerService#getDeviceOwnerName
            mDeviceOwner = new OwnerInfo(null, target, mDeviceOwner.remoteBugreportUri,
                    mDeviceOwner.remoteBugreportHash, mDeviceOwner.isOrganizationOwnedDevice);
            if (previousDeviceOwnerType != null) {
                mDeviceOwnerTypes.put(mDeviceOwner.packageName, previousDeviceOwnerType);
            }
            if (previousProtectedPackages != null) {
                mDeviceOwnerProtectedPackages.put(
                        mDeviceOwner.packageName, previousProtectedPackages);
            }
            notifyChangeLocked();
            pushToActivityTaskManagerLocked();
        }
    }

    ComponentName getProfileOwnerComponent(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.admin : null;
        }
    }

    String getProfileOwnerName(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.name : null;
        }
    }

    String getProfileOwnerPackage(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.packageName : null;
        }
    }

    /**
     * Returns true if {@code userId} has a profile owner and that profile owner is on an
     * organization-owned device, as indicated by the provisioning flow.
     */
    boolean isProfileOwnerOfOrganizationOwnedDevice(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.isOrganizationOwnedDevice : false;
        }
    }

    Set<Integer> getProfileOwnerKeys() {
        synchronized (mLock) {
            return mProfileOwners.keySet();
        }
    }

    List<OwnerShellData> listAllOwners() {
        List<OwnerShellData> owners = new ArrayList<>();
        synchronized (mLock) {
            if (mDeviceOwner != null) {
                owners.add(OwnerShellData.forDeviceOwner(mDeviceOwnerUserId, mDeviceOwner.admin));
            }
            for (int i = 0; i < mProfileOwners.size(); i++) {
                int userId = mProfileOwners.keyAt(i);
                OwnerInfo info = mProfileOwners.valueAt(i);
                owners.add(OwnerShellData.forUserProfileOwner(userId, info.admin));
            }
        }
        return owners;
    }


    SystemUpdatePolicy getSystemUpdatePolicy() {
        synchronized (mLock) {
            return mSystemUpdatePolicy;
        }
    }

    void setSystemUpdatePolicy(SystemUpdatePolicy systemUpdatePolicy) {
        synchronized (mLock) {
            mSystemUpdatePolicy = systemUpdatePolicy;
        }
    }

    void clearSystemUpdatePolicy() {
        synchronized (mLock) {
            mSystemUpdatePolicy = null;
        }
    }

    Pair<LocalDate, LocalDate> getSystemUpdateFreezePeriodRecord() {
        synchronized (mLock) {
            return new Pair<>(mSystemUpdateFreezeStart, mSystemUpdateFreezeEnd);
        }
    }

    String getSystemUpdateFreezePeriodRecordAsString() {
        StringBuilder freezePeriodRecord = new StringBuilder();
        freezePeriodRecord.append("start: ");
        if (mSystemUpdateFreezeStart != null) {
            freezePeriodRecord.append(mSystemUpdateFreezeStart.toString());
        } else {
            freezePeriodRecord.append("null");
        }
        freezePeriodRecord.append("; end: ");
        if (mSystemUpdateFreezeEnd != null) {
            freezePeriodRecord.append(mSystemUpdateFreezeEnd.toString());
        } else {
            freezePeriodRecord.append("null");
        }
        return freezePeriodRecord.toString();
    }

    /**
     * Returns {@code true} if the freeze period record is changed, {@code false} otherwise.
     */
    boolean setSystemUpdateFreezePeriodRecord(LocalDate start, LocalDate end) {
        boolean changed = false;
        synchronized (mLock) {
            if (!Objects.equals(mSystemUpdateFreezeStart, start)) {
                mSystemUpdateFreezeStart = start;
                changed = true;
            }
            if (!Objects.equals(mSystemUpdateFreezeEnd, end)) {
                mSystemUpdateFreezeEnd = end;
                changed = true;
            }
        }
        return changed;
    }

    boolean hasDeviceOwner() {
        synchronized (mLock) {
            return mDeviceOwner != null;
        }
    }

    boolean isDeviceOwnerUserId(int userId) {
        synchronized (mLock) {
            return mDeviceOwner != null && mDeviceOwnerUserId == userId;
        }
    }

    boolean hasProfileOwner(int userId) {
        synchronized (mLock) {
            return getProfileOwnerComponent(userId) != null;
        }
    }

    /** Sets the remote bugreport uri and hash, and also writes to the file. */
    void setDeviceOwnerRemoteBugreportUriAndHash(String remoteBugreportUri,
            String remoteBugreportHash) {
        synchronized (mLock) {
            if (mDeviceOwner != null) {
                mDeviceOwner.remoteBugreportUri = remoteBugreportUri;
                mDeviceOwner.remoteBugreportHash = remoteBugreportHash;
            }
            writeDeviceOwner();
        }
    }

    /** Set whether the profile owner manages an organization-owned device, then write to file. */
    void setProfileOwnerOfOrganizationOwnedDevice(int userId, boolean isOrganizationOwnedDevice) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
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
        synchronized (mLock) {
            if (!hasDeviceOwner()) {
                Slog.e(TAG, "Attempting to set a device owner type when there is no device owner");
                return;
            } else if (!isAdminTestOnly && isDeviceOwnerTypeSetForDeviceOwner(packageName)) {
                Slog.e(TAG, "Setting the device owner type more than once is only allowed"
                        + " for test only admins");
                return;
            }

            mDeviceOwnerTypes.put(packageName, deviceOwnerType);
            writeDeviceOwner();
        }
    }

    @DeviceOwnerType
    int getDeviceOwnerType(String packageName) {
        synchronized (mLock) {
            if (isDeviceOwnerTypeSetForDeviceOwner(packageName)) {
                return mDeviceOwnerTypes.get(packageName);
            }
            return DEVICE_OWNER_TYPE_DEFAULT;
        }
    }

    boolean isDeviceOwnerTypeSetForDeviceOwner(String packageName) {
        synchronized (mLock) {
            return !mDeviceOwnerTypes.isEmpty() && mDeviceOwnerTypes.containsKey(packageName);
        }
    }

    void setDeviceOwnerProtectedPackages(String packageName, List<String> protectedPackages) {
        synchronized (mLock) {
            if (!hasDeviceOwner()) {
                Slog.e(TAG,
                        "Attempting to set device owner protected packages when there is no "
                                + "device owner");
                return;
            } else if (!mDeviceOwner.packageName.equals(packageName)) {
                Slog.e(TAG, "Attempting to set device owner protected packages when the provided "
                        + "package name " + packageName
                        + " does not match the device owner package name");
                return;
            }

            mDeviceOwnerProtectedPackages.put(packageName, protectedPackages);
            mPackageManagerInternal.setDeviceOwnerProtectedPackages(packageName, protectedPackages);
            writeDeviceOwner();
        }
    }

    List<String> getDeviceOwnerProtectedPackages(String packageName) {
        synchronized (mLock) {
            return mDeviceOwnerProtectedPackages.containsKey(packageName)
                    ? mDeviceOwnerProtectedPackages.get(packageName) : Collections.emptyList();
        }
    }

    void writeDeviceOwner() {
        synchronized (mLock) {
            if (DEBUG) {
                Log.d(TAG, "Writing to device owner file");
            }
            pushToDevicePolicyManager();
            new DeviceOwnerReadWriter().writeToFileLocked();
        }
    }

    void writeProfileOwner(int userId) {
        synchronized (mLock) {
            if (DEBUG) {
                Log.d(TAG, "Writing to profile owner file for user " + userId);
            }
            pushToDevicePolicyManager();
            new ProfileOwnerReadWriter(userId).writeToFileLocked();
        }
    }

    /**
     * Saves the given {@link SystemUpdateInfo} if it is different from the existing one, or if
     * none exists.
     *
     * @return Whether the saved system update information has changed.
     */
    boolean saveSystemUpdateInfo(@Nullable SystemUpdateInfo newInfo) {
        synchronized (mLock) {
            // Check if we already have the same update information.
            if (Objects.equals(newInfo, mSystemUpdateInfo)) {
                return false;
            }

            mSystemUpdateInfo = newInfo;
            new DeviceOwnerReadWriter().writeToFileLocked();
            return true;
        }
    }

    @Nullable
    public SystemUpdateInfo getSystemUpdateInfo() {
        synchronized (mLock) {
            return mSystemUpdateInfo;
        }
    }

    void pushToAppOpsLocked() {
        if (!mSystemReady) {
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            final SparseIntArray owners = new SparseIntArray();
            if (mDeviceOwner != null) {
                final int uid = getDeviceOwnerUidLocked();
                if (uid >= 0) {
                    owners.put(mDeviceOwnerUserId, uid);
                }
            }
            if (mProfileOwners != null) {
                for (int poi = mProfileOwners.size() - 1; poi >= 0; poi--) {
                    final int uid = mPackageManagerInternal.getPackageUid(
                            mProfileOwners.valueAt(poi).packageName,
                            PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                            mProfileOwners.keyAt(poi));
                    if (uid >= 0) {
                        owners.put(mProfileOwners.keyAt(poi), uid);
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
        synchronized (mLock) {
            mSystemReady = true;
            pushToActivityManagerLocked();
            pushToAppOpsLocked();
        }
    }

    private abstract static class FileReadWriter {
        private final File mFile;

        protected FileReadWriter(File file) {
            mFile = file;
        }

        abstract boolean shouldWrite();

        void writeToFileLocked() {
            if (!shouldWrite()) {
                if (DEBUG) {
                    Log.d(TAG, "No need to write to " + mFile);
                }
                // No contents, remove the file.
                if (mFile.exists()) {
                    if (DEBUG) {
                        Log.d(TAG, "Deleting existing " + mFile);
                    }
                    if (!mFile.delete()) {
                        Slog.e(TAG, "Failed to remove " + mFile.getPath());
                    }
                }
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Writing to " + mFile);
            }

            final AtomicFile f = new AtomicFile(mFile);
            FileOutputStream outputStream = null;
            try {
                outputStream = f.startWrite();
                final TypedXmlSerializer out = Xml.resolveSerializer(outputStream);

                // Root tag
                out.startDocument(null, true);
                out.startTag(null, TAG_ROOT);

                // Actual content
                writeInner(out);

                // Close root
                out.endTag(null, TAG_ROOT);
                out.endDocument();
                out.flush();

                // Commit the content.
                f.finishWrite(outputStream);
                outputStream = null;

            } catch (IOException e) {
                Slog.e(TAG, "Exception when writing", e);
                if (outputStream != null) {
                    f.failWrite(outputStream);
                }
            }
        }

        void readFromFileLocked() {
            if (!mFile.exists()) {
                if (DEBUG) {
                    Log.d(TAG, "" + mFile + " doesn't exist");
                }
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "Reading from " + mFile);
            }
            final AtomicFile f = new AtomicFile(mFile);
            InputStream input = null;
            try {
                input = f.openRead();
                final TypedXmlPullParser parser = Xml.resolvePullParser(input);

                int type;
                int depth = 0;
                while ((type = parser.next()) != TypedXmlPullParser.END_DOCUMENT) {
                    switch (type) {
                        case TypedXmlPullParser.START_TAG:
                            depth++;
                            break;
                        case TypedXmlPullParser.END_TAG:
                            depth--;
                            // fallthrough
                        default:
                            continue;
                    }
                    // Check the root tag
                    final String tag = parser.getName();
                    if (depth == 1) {
                        if (!TAG_ROOT.equals(tag)) {
                            Slog.e(TAG, "Invalid root tag: " + tag);
                            return;
                        }
                        continue;
                    }
                    // readInner() will only see START_TAG at depth >= 2.
                    if (!readInner(parser, depth, tag)) {
                        return; // Error
                    }
                }
            } catch (XmlPullParserException | IOException e) {
                Slog.e(TAG, "Error parsing owners information file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        abstract void writeInner(TypedXmlSerializer out) throws IOException;

        abstract boolean readInner(TypedXmlPullParser parser, int depth, String tag);
    }

    private class DeviceOwnerReadWriter extends FileReadWriter {

        protected DeviceOwnerReadWriter() {
            super(getDeviceOwnerFile());
        }

        @Override
        boolean shouldWrite() {
            return (mDeviceOwner != null) || (mSystemUpdatePolicy != null)
                    || (mSystemUpdateInfo != null);
        }

        @Override
        void writeInner(TypedXmlSerializer out) throws IOException {
            if (mDeviceOwner != null) {
                mDeviceOwner.writeToXml(out, TAG_DEVICE_OWNER);
                out.startTag(null, TAG_DEVICE_OWNER_CONTEXT);
                out.attributeInt(null, ATTR_USERID, mDeviceOwnerUserId);
                out.endTag(null, TAG_DEVICE_OWNER_CONTEXT);

            }

            if (!mDeviceOwnerTypes.isEmpty()) {
                for (ArrayMap.Entry<String, Integer> entry : mDeviceOwnerTypes.entrySet()) {
                    out.startTag(null, TAG_DEVICE_OWNER_TYPE);
                    out.attribute(null, ATTR_PACKAGE, entry.getKey());
                    out.attributeInt(null, ATTR_DEVICE_OWNER_TYPE_VALUE, entry.getValue());
                    out.endTag(null, TAG_DEVICE_OWNER_TYPE);
                }
            }

            if (!mDeviceOwnerProtectedPackages.isEmpty()) {
                for (ArrayMap.Entry<String, List<String>> entry :
                        mDeviceOwnerProtectedPackages.entrySet()) {
                    List<String> protectedPackages = entry.getValue();

                    out.startTag(null, TAG_DEVICE_OWNER_PROTECTED_PACKAGES);
                    out.attribute(null, ATTR_PACKAGE, entry.getKey());
                    out.attributeInt(null, ATTR_SIZE, protectedPackages.size());
                    for (int i = 0, size = protectedPackages.size(); i < size; i++) {
                        out.attribute(null, ATTR_NAME + i, protectedPackages.get(i));
                    }
                    out.endTag(null, TAG_DEVICE_OWNER_PROTECTED_PACKAGES);
                }
            }

            if (mSystemUpdatePolicy != null) {
                out.startTag(null, TAG_SYSTEM_UPDATE_POLICY);
                mSystemUpdatePolicy.saveToXml(out);
                out.endTag(null, TAG_SYSTEM_UPDATE_POLICY);
            }

            if (mSystemUpdateInfo != null) {
                mSystemUpdateInfo.writeToXml(out, TAG_PENDING_OTA_INFO);
            }

            if (mSystemUpdateFreezeStart != null || mSystemUpdateFreezeEnd != null) {
                out.startTag(null, TAG_FREEZE_PERIOD_RECORD);
                if (mSystemUpdateFreezeStart != null) {
                    out.attribute(null, ATTR_FREEZE_RECORD_START,
                            mSystemUpdateFreezeStart.toString());
                }
                if (mSystemUpdateFreezeEnd != null) {
                    out.attribute(null, ATTR_FREEZE_RECORD_END, mSystemUpdateFreezeEnd.toString());
                }
                out.endTag(null, TAG_FREEZE_PERIOD_RECORD);
            }
        }

        @Override
        boolean readInner(TypedXmlPullParser parser, int depth, String tag) {
            if (depth > 2) {
                return true; // Ignore
            }
            switch (tag) {
                case TAG_DEVICE_OWNER:
                    mDeviceOwner = OwnerInfo.readFromXml(parser);
                    mDeviceOwnerUserId = UserHandle.USER_SYSTEM; // Set default
                    break;
                case TAG_DEVICE_OWNER_CONTEXT: {
                    mDeviceOwnerUserId = parser.getAttributeInt(null, ATTR_USERID,
                            mDeviceOwnerUserId);
                    break;
                }
                case TAG_SYSTEM_UPDATE_POLICY:
                    mSystemUpdatePolicy = SystemUpdatePolicy.restoreFromXml(parser);
                    break;
                case TAG_PENDING_OTA_INFO:
                    mSystemUpdateInfo = SystemUpdateInfo.readFromXml(parser);
                    break;
                case TAG_FREEZE_PERIOD_RECORD:
                    String startDate = parser.getAttributeValue(null, ATTR_FREEZE_RECORD_START);
                    String endDate = parser.getAttributeValue(null, ATTR_FREEZE_RECORD_END);
                    if (startDate != null && endDate != null) {
                        mSystemUpdateFreezeStart = LocalDate.parse(startDate);
                        mSystemUpdateFreezeEnd = LocalDate.parse(endDate);
                        if (mSystemUpdateFreezeStart.isAfter(mSystemUpdateFreezeEnd)) {
                            Slog.e(TAG, "Invalid system update freeze record loaded");
                            mSystemUpdateFreezeStart = null;
                            mSystemUpdateFreezeEnd = null;
                        }
                    }
                    break;
                case TAG_DEVICE_OWNER_TYPE:
                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    int deviceOwnerType = parser.getAttributeInt(null, ATTR_DEVICE_OWNER_TYPE_VALUE,
                            DEVICE_OWNER_TYPE_DEFAULT);
                    mDeviceOwnerTypes.put(packageName, deviceOwnerType);
                    break;
                case TAG_DEVICE_OWNER_PROTECTED_PACKAGES:
                    packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    int protectedPackagesSize = parser.getAttributeInt(null, ATTR_SIZE, 0);
                    List<String> protectedPackages = new ArrayList<>();
                    for (int i = 0; i < protectedPackagesSize; i++) {
                        protectedPackages.add(parser.getAttributeValue(null, ATTR_NAME + i));
                    }
                    mDeviceOwnerProtectedPackages.put(packageName, protectedPackages);
                    break;
                default:
                    Slog.e(TAG, "Unexpected tag: " + tag);
                    return false;
            }
            return true;
        }
    }

    private class ProfileOwnerReadWriter extends FileReadWriter {
        private final int mUserId;

        ProfileOwnerReadWriter(int userId) {
            super(getProfileOwnerFile(userId));
            mUserId = userId;
        }

        @Override
        boolean shouldWrite() {
            return mProfileOwners.get(mUserId) != null;
        }

        @Override
        void writeInner(TypedXmlSerializer out) throws IOException {
            final OwnerInfo profileOwner = mProfileOwners.get(mUserId);
            if (profileOwner != null) {
                profileOwner.writeToXml(out, TAG_PROFILE_OWNER);
            }
        }

        @Override
        boolean readInner(TypedXmlPullParser parser, int depth, String tag) {
            if (depth > 2) {
                return true; // Ignore
            }
            switch (tag) {
                case TAG_PROFILE_OWNER:
                    mProfileOwners.put(mUserId, OwnerInfo.readFromXml(parser));
                    break;
                default:
                    Slog.e(TAG, "Unexpected tag: " + tag);
                    return false;

            }
            return true;
        }
    }

    static class OwnerInfo {
        public final String name;
        public final String packageName;
        public final ComponentName admin;
        public String remoteBugreportUri;
        public String remoteBugreportHash;
        public boolean isOrganizationOwnedDevice;

        OwnerInfo(String name, ComponentName admin,
                String remoteBugreportUri, String remoteBugreportHash,
                boolean isOrganizationOwnedDevice) {
            this.name = name;
            this.admin = admin;
            this.packageName = admin.getPackageName();
            this.remoteBugreportUri = remoteBugreportUri;
            this.remoteBugreportHash = remoteBugreportHash;
            this.isOrganizationOwnedDevice = isOrganizationOwnedDevice;
        }

        public void writeToXml(TypedXmlSerializer out, String tag) throws IOException {
            out.startTag(null, tag);
            if (name != null) {
                out.attribute(null, ATTR_NAME, name);
            }
            if (admin != null) {
                out.attribute(null, ATTR_COMPONENT_NAME, admin.flattenToString());
            }
            if (remoteBugreportUri != null) {
                out.attribute(null, ATTR_REMOTE_BUGREPORT_URI, remoteBugreportUri);
            }
            if (remoteBugreportHash != null) {
                out.attribute(null, ATTR_REMOTE_BUGREPORT_HASH, remoteBugreportHash);
            }
            if (isOrganizationOwnedDevice) {
                out.attributeBoolean(null, ATTR_PROFILE_OWNER_OF_ORG_OWNED_DEVICE,
                        isOrganizationOwnedDevice);
            }
            out.endTag(null, tag);
        }

        public static OwnerInfo readFromXml(TypedXmlPullParser parser) {
            final String name = parser.getAttributeValue(null, ATTR_NAME);
            final String componentName =
                    parser.getAttributeValue(null, ATTR_COMPONENT_NAME);
            final String remoteBugreportUri = parser.getAttributeValue(null,
                    ATTR_REMOTE_BUGREPORT_URI);
            final String remoteBugreportHash = parser.getAttributeValue(null,
                    ATTR_REMOTE_BUGREPORT_HASH);
            final String canAccessDeviceIdsStr =
                    parser.getAttributeValue(null, ATTR_CAN_ACCESS_DEVICE_IDS);
            final boolean canAccessDeviceIds =
                    ("true".equals(canAccessDeviceIdsStr));
            final String isOrgOwnedDeviceStr =
                    parser.getAttributeValue(null, ATTR_PROFILE_OWNER_OF_ORG_OWNED_DEVICE);
            final boolean isOrgOwnedDevice =
                    ("true".equals(isOrgOwnedDeviceStr)) | canAccessDeviceIds;

            if (componentName == null) {
                Slog.e(TAG, "Owner component not found");
                return null;
            }
            final ComponentName admin = ComponentName.unflattenFromString(componentName);
            if (admin == null) {
                Slog.e(TAG, "Owner component not parsable: " + componentName);
                return null;
            }

            return new OwnerInfo(
                        name, admin, remoteBugreportUri, remoteBugreportHash, isOrgOwnedDevice);
        }

        public void dump(IndentingPrintWriter pw) {
            pw.println("admin=" + admin);
            pw.println("name=" + name);
            pw.println("package=" + packageName);
            pw.println("isOrganizationOwnedDevice=" + isOrganizationOwnedDevice);
        }
    }

    public void dump(IndentingPrintWriter pw) {
        boolean needBlank = false;
        if (mDeviceOwner != null) {
            pw.println("Device Owner: ");
            pw.increaseIndent();
            mDeviceOwner.dump(pw);
            pw.println("User ID: " + mDeviceOwnerUserId);
            pw.decreaseIndent();
            needBlank = true;
        }
        if (mSystemUpdatePolicy != null) {
            if (needBlank) {
                pw.println();
            }
            pw.println("System Update Policy: " + mSystemUpdatePolicy);
            needBlank = true;
        }
        if (mProfileOwners != null) {
            for (Map.Entry<Integer, OwnerInfo> entry : mProfileOwners.entrySet()) {
                if (needBlank) {
                    pw.println();
                }
                pw.println("Profile Owner (User " + entry.getKey() + "): ");
                pw.increaseIndent();
                entry.getValue().dump(pw);
                pw.decreaseIndent();
                needBlank = true;
            }
        }
        if (mSystemUpdateInfo != null) {
            if (needBlank) {
                pw.println();
            }
            pw.println("Pending System Update: " + mSystemUpdateInfo);
            needBlank = true;
        }
        if (mSystemUpdateFreezeStart != null || mSystemUpdateFreezeEnd != null) {
            if (needBlank) {
                pw.println();
            }
            pw.println("System update freeze record: "
                    + getSystemUpdateFreezePeriodRecordAsString());
            needBlank = true;
        }
    }

    @VisibleForTesting
    File getDeviceOwnerFile() {
        return new File(mInjector.environmentGetDataSystemDirectory(), DEVICE_OWNER_XML);
    }

    @VisibleForTesting
    File getProfileOwnerFile(int userId) {
        return new File(mInjector.environmentGetUserSystemDirectory(userId), PROFILE_OWNER_XML);
    }

    @VisibleForTesting
    public static class Injector {
        File environmentGetDataSystemDirectory() {
            return Environment.getDataSystemDirectory();
        }

        File environmentGetUserSystemDirectory(int userId) {
            return Environment.getUserSystemDirectory(userId);
        }
    }
}
