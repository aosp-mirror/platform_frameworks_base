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

import android.annotation.Nullable;
import android.app.AppOpsManagerInternal;
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.LocalServices;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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

    private static final String DEVICE_OWNER_XML_LEGACY = "device_owner.xml";

    // XML storing device owner info, system update policy and pending OTA update information.
    private static final String DEVICE_OWNER_XML = "device_owner_2.xml";

    private static final String PROFILE_OWNER_XML = "profile_owner.xml";

    private static final String TAG_ROOT = "root";

    private static final String TAG_DEVICE_OWNER = "device-owner";
    private static final String TAG_DEVICE_INITIALIZER = "device-initializer";
    private static final String TAG_SYSTEM_UPDATE_POLICY = "system-update-policy";
    private static final String TAG_FREEZE_PERIOD_RECORD = "freeze-record";
    private static final String TAG_PENDING_OTA_INFO = "pending-ota-info";
    private static final String TAG_PROFILE_OWNER = "profile-owner";
    // Holds "context" for device-owner, this must not be show up before device-owner.
    private static final String TAG_DEVICE_OWNER_CONTEXT = "device-owner-context";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_COMPONENT_NAME = "component";
    private static final String ATTR_REMOTE_BUGREPORT_URI = "remoteBugreportUri";
    private static final String ATTR_REMOTE_BUGREPORT_HASH = "remoteBugreportHash";
    private static final String ATTR_USERID = "userId";
    private static final String ATTR_USER_RESTRICTIONS_MIGRATED = "userRestrictionsMigrated";
    private static final String ATTR_FREEZE_RECORD_START = "start";
    private static final String ATTR_FREEZE_RECORD_END = "end";
    private static final String ATTR_CAN_ACCESS_DEVICE_IDS = "canAccessDeviceIds";

    private final UserManager mUserManager;
    private final UserManagerInternal mUserManagerInternal;
    private final PackageManagerInternal mPackageManagerInternal;

    private boolean mSystemReady;

    // Internal state for the device owner package.
    private OwnerInfo mDeviceOwner;

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
            PackageManagerInternal packageManagerInternal) {
        this(userManager, userManagerInternal, packageManagerInternal, new Injector());
    }

    @VisibleForTesting
    Owners(UserManager userManager,
            UserManagerInternal userManagerInternal,
            PackageManagerInternal packageManagerInternal,
            Injector injector) {
        mUserManager = userManager;
        mUserManagerInternal = userManagerInternal;
        mPackageManagerInternal = packageManagerInternal;
        mInjector = injector;
    }

    /**
     * Load configuration from the disk.
     */
    void load() {
        synchronized (mLock) {
            // First, try to read from the legacy file.
            final File legacy = getLegacyConfigFile();

            final List<UserInfo> users = mUserManager.getUsers(true);

            if (readLegacyOwnerFileLocked(legacy)) {
                if (DEBUG) {
                    Log.d(TAG, "Legacy config file found.");
                }

                // Legacy file exists, write to new files and remove the legacy one.
                writeDeviceOwner();
                for (int userId : getProfileOwnerKeys()) {
                    writeProfileOwner(userId);
                }
                if (DEBUG) {
                    Log.d(TAG, "Deleting legacy config file");
                }
                if (!legacy.delete()) {
                    Slog.e(TAG, "Failed to remove the legacy setting file");
                }
            } else {
                // No legacy file, read from the new format files.
                new DeviceOwnerReadWriter().readFromFileLocked();

                for (UserInfo ui : users) {
                    new ProfileOwnerReadWriter(ui.id).readFromFileLocked();
                }
            }
            mUserManagerInternal.setDeviceManaged(hasDeviceOwner());
            for (UserInfo ui : users) {
                mUserManagerInternal.setUserManaged(ui.id, hasProfileOwner(ui.id));
            }
            if (hasDeviceOwner() && hasProfileOwner(getDeviceOwnerUserId())) {
                Slog.w(TAG, String.format("User %d has both DO and PO, which is not supported",
                        getDeviceOwnerUserId()));
            }
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
        }
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
            // For a newly set DO, there's no need for migration.
            setDeviceOwnerWithRestrictionsMigrated(admin, ownerName, userId,
                    /* userRestrictionsMigrated =*/ true);
        }
    }

    // Note this should be only called during migration.  Normally when DO is set,
    // userRestrictionsMigrated should always be true.
    void setDeviceOwnerWithRestrictionsMigrated(ComponentName admin, String ownerName, int userId,
            boolean userRestrictionsMigrated) {
        synchronized (mLock) {
            // A device owner is allowed to access device identifiers. Even though this flag
            // is not currently checked for device owner, it is set to true here so that it is
            // semantically compatible with the meaning of this flag.
            mDeviceOwner = new OwnerInfo(ownerName, admin, userRestrictionsMigrated,
                    /* remoteBugreportUri =*/ null, /* remoteBugreportHash =*/
                    null, /* canAccessDeviceIds =*/true);
            mDeviceOwnerUserId = userId;

            mUserManagerInternal.setDeviceManaged(true);
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
        }
    }

    void clearDeviceOwner() {
        synchronized (mLock) {
            mDeviceOwner = null;
            mDeviceOwnerUserId = UserHandle.USER_NULL;

            mUserManagerInternal.setDeviceManaged(false);
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
        }
    }

    void setProfileOwner(ComponentName admin, String ownerName, int userId) {
        synchronized (mLock) {
            // For a newly set PO, there's no need for migration.
            mProfileOwners.put(userId, new OwnerInfo(ownerName, admin,
                    /* userRestrictionsMigrated =*/ true, /* remoteBugreportUri =*/ null,
                    /* remoteBugreportHash =*/ null, /* canAccessDeviceIds =*/ false));
            mUserManagerInternal.setUserManaged(userId, true);
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
        }
    }

    void removeProfileOwner(int userId) {
        synchronized (mLock) {
            mProfileOwners.remove(userId);
            mUserManagerInternal.setUserManaged(userId, false);
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
        }
    }

    void transferProfileOwner(ComponentName target, int userId) {
        synchronized (mLock) {
            final OwnerInfo ownerInfo = mProfileOwners.get(userId);
            final OwnerInfo newOwnerInfo = new OwnerInfo(target.getPackageName(), target,
                    ownerInfo.userRestrictionsMigrated, ownerInfo.remoteBugreportUri,
                    ownerInfo.remoteBugreportHash, /* canAccessDeviceIds =*/
                    ownerInfo.canAccessDeviceIds);
            mProfileOwners.put(userId, newOwnerInfo);
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
        }
    }

    void transferDeviceOwnership(ComponentName target) {
        synchronized (mLock) {
            // We don't set a name because it's not used anyway.
            // See DevicePolicyManagerService#getDeviceOwnerName
            mDeviceOwner = new OwnerInfo(null, target,
                    mDeviceOwner.userRestrictionsMigrated, mDeviceOwner.remoteBugreportUri,
                    mDeviceOwner.remoteBugreportHash, /* canAccessDeviceIds =*/
                    mDeviceOwner.canAccessDeviceIds);
            pushToPackageManagerLocked();
            pushToAppOpsLocked();
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
     * Returns true if {@code userId} has a profile owner and that profile owner was granted
     * the ability to access device identifiers.
     */
    boolean canProfileOwnerAccessDeviceIds(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            return profileOwner != null ? profileOwner.canAccessDeviceIds : false;
        }
    }

    Set<Integer> getProfileOwnerKeys() {
        synchronized (mLock) {
            return mProfileOwners.keySet();
        }
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

    /**
     * @return true if user restrictions need to be migrated for DO.
     */
    boolean getDeviceOwnerUserRestrictionsNeedsMigration() {
        synchronized (mLock) {
            return mDeviceOwner != null && !mDeviceOwner.userRestrictionsMigrated;
        }
    }

    /**
     * @return true if user restrictions need to be migrated for PO.
     */
    boolean getProfileOwnerUserRestrictionsNeedsMigration(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            return profileOwner != null && !profileOwner.userRestrictionsMigrated;
        }
    }

    /** Sets the user restrictions migrated flag, and also writes to the file. */
    void setDeviceOwnerUserRestrictionsMigrated() {
        synchronized (mLock) {
            if (mDeviceOwner != null) {
                mDeviceOwner.userRestrictionsMigrated = true;
            }
            writeDeviceOwner();
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

    /** Sets the user restrictions migrated flag, and also writes to the file.  */
    void setProfileOwnerUserRestrictionsMigrated(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            if (profileOwner != null) {
                profileOwner.userRestrictionsMigrated = true;
            }
            writeProfileOwner(userId);
        }
    }

    /** Sets the grant to access device IDs, and also writes to file. */
    void setProfileOwnerCanAccessDeviceIds(int userId) {
        synchronized (mLock) {
            OwnerInfo profileOwner = mProfileOwners.get(userId);
            if (profileOwner != null) {
                profileOwner.canAccessDeviceIds = true;
            } else {
                Slog.e(TAG, String.format(
                        "Cannot grant Device IDs access for user %d, no profile owner.", userId));
            }
            writeProfileOwner(userId);
        }
    }

    private boolean readLegacyOwnerFileLocked(File file) {
        if (!file.exists()) {
            // Already migrated or the device has no owners.
            return false;
        }
        try {
            InputStream input = new AtomicFile(file).openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, StandardCharsets.UTF_8.name());
            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type!=XmlPullParser.START_TAG) {
                    continue;
                }

                String tag = parser.getName();
                if (tag.equals(TAG_DEVICE_OWNER)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    mDeviceOwner = new OwnerInfo(name, packageName,
                            /* userRestrictionsMigrated =*/ false, /* remoteBugreportUri =*/ null,
                            /* remoteBugreportHash =*/ null, /* canAccessDeviceIds =*/ true);
                    mDeviceOwnerUserId = UserHandle.USER_SYSTEM;
                } else if (tag.equals(TAG_DEVICE_INITIALIZER)) {
                    // Deprecated tag
                } else if (tag.equals(TAG_PROFILE_OWNER)) {
                    String profileOwnerPackageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    String profileOwnerName = parser.getAttributeValue(null, ATTR_NAME);
                    String profileOwnerComponentStr =
                            parser.getAttributeValue(null, ATTR_COMPONENT_NAME);
                    int userId = Integer.parseInt(parser.getAttributeValue(null, ATTR_USERID));
                    OwnerInfo profileOwnerInfo = null;
                    if (profileOwnerComponentStr != null) {
                        ComponentName admin = ComponentName.unflattenFromString(
                                profileOwnerComponentStr);
                        if (admin != null) {
                            profileOwnerInfo = new OwnerInfo(profileOwnerName, admin,
                                    /* userRestrictionsMigrated =*/ false, null,
                                    null, /* canAccessDeviceIds =*/ false);
                        } else {
                            // This shouldn't happen but switch from package name -> component name
                            // might have written bad device owner files. b/17652534
                            Slog.e(TAG, "Error parsing device-owner file. Bad component name " +
                                    profileOwnerComponentStr);
                        }
                    }
                    if (profileOwnerInfo == null) {
                        profileOwnerInfo = new OwnerInfo(profileOwnerName, profileOwnerPackageName,
                                /* userRestrictionsMigrated =*/ false,
                                /* remoteBugreportUri =*/ null, /* remoteBugreportHash =*/
                                null, /* canAccessDeviceIds =*/ false);
                    }
                    mProfileOwners.put(userId, profileOwnerInfo);
                } else if (TAG_SYSTEM_UPDATE_POLICY.equals(tag)) {
                    mSystemUpdatePolicy = SystemUpdatePolicy.restoreFromXml(parser);
                } else {
                    throw new XmlPullParserException(
                            "Unexpected tag in device owner file: " + tag);
                }
            }
            input.close();
        } catch (XmlPullParserException|IOException e) {
            Slog.e(TAG, "Error parsing device-owner file", e);
        }
        return true;
    }

    void writeDeviceOwner() {
        synchronized (mLock) {
            if (DEBUG) {
                Log.d(TAG, "Writing to device owner file");
            }
            new DeviceOwnerReadWriter().writeToFileLocked();
        }
    }

    void writeProfileOwner(int userId) {
        synchronized (mLock) {
            if (DEBUG) {
                Log.d(TAG, "Writing to profile owner file for user " + userId);
            }
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
                final int uid = mPackageManagerInternal.getPackageUid(mDeviceOwner.packageName,
                        PackageManager.MATCH_ALL | PackageManager.MATCH_KNOWN_PACKAGES,
                        mDeviceOwnerUserId);
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
                final XmlSerializer out = new FastXmlSerializer();
                out.setOutput(outputStream, StandardCharsets.UTF_8.name());

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
                final XmlPullParser parser = Xml.newPullParser();
                parser.setInput(input, StandardCharsets.UTF_8.name());

                int type;
                int depth = 0;
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    switch (type) {
                        case XmlPullParser.START_TAG:
                            depth++;
                            break;
                        case XmlPullParser.END_TAG:
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

        abstract void writeInner(XmlSerializer out) throws IOException;

        abstract boolean readInner(XmlPullParser parser, int depth, String tag);
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
        void writeInner(XmlSerializer out) throws IOException {
            if (mDeviceOwner != null) {
                mDeviceOwner.writeToXml(out, TAG_DEVICE_OWNER);
                out.startTag(null, TAG_DEVICE_OWNER_CONTEXT);
                out.attribute(null, ATTR_USERID, String.valueOf(mDeviceOwnerUserId));
                out.endTag(null, TAG_DEVICE_OWNER_CONTEXT);
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
        boolean readInner(XmlPullParser parser, int depth, String tag) {
            if (depth > 2) {
                return true; // Ignore
            }
            switch (tag) {
                case TAG_DEVICE_OWNER:
                    mDeviceOwner = OwnerInfo.readFromXml(parser);
                    mDeviceOwnerUserId = UserHandle.USER_SYSTEM; // Set default
                    break;
                case TAG_DEVICE_OWNER_CONTEXT: {
                    final String userIdString =
                            parser.getAttributeValue(null, ATTR_USERID);
                    try {
                        mDeviceOwnerUserId = Integer.parseInt(userIdString);
                    } catch (NumberFormatException e) {
                        Slog.e(TAG, "Error parsing user-id " + userIdString);
                    }
                    break;
                }
                case TAG_DEVICE_INITIALIZER:
                    // Deprecated tag
                    break;
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
        void writeInner(XmlSerializer out) throws IOException {
            final OwnerInfo profileOwner = mProfileOwners.get(mUserId);
            if (profileOwner != null) {
                profileOwner.writeToXml(out, TAG_PROFILE_OWNER);
            }
        }

        @Override
        boolean readInner(XmlPullParser parser, int depth, String tag) {
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
        public boolean userRestrictionsMigrated;
        public String remoteBugreportUri;
        public String remoteBugreportHash;
        public boolean canAccessDeviceIds;

        public OwnerInfo(String name, String packageName, boolean userRestrictionsMigrated,
                String remoteBugreportUri, String remoteBugreportHash, boolean canAccessDeviceIds) {
            this.name = name;
            this.packageName = packageName;
            this.admin = new ComponentName(packageName, "");
            this.userRestrictionsMigrated = userRestrictionsMigrated;
            this.remoteBugreportUri = remoteBugreportUri;
            this.remoteBugreportHash = remoteBugreportHash;
            this.canAccessDeviceIds = canAccessDeviceIds;
        }

        public OwnerInfo(String name, ComponentName admin, boolean userRestrictionsMigrated,
                String remoteBugreportUri, String remoteBugreportHash, boolean canAccessDeviceIds) {
            this.name = name;
            this.admin = admin;
            this.packageName = admin.getPackageName();
            this.userRestrictionsMigrated = userRestrictionsMigrated;
            this.remoteBugreportUri = remoteBugreportUri;
            this.remoteBugreportHash = remoteBugreportHash;
            this.canAccessDeviceIds = canAccessDeviceIds;
        }

        public void writeToXml(XmlSerializer out, String tag) throws IOException {
            out.startTag(null, tag);
            out.attribute(null, ATTR_PACKAGE, packageName);
            if (name != null) {
                out.attribute(null, ATTR_NAME, name);
            }
            if (admin != null) {
                out.attribute(null, ATTR_COMPONENT_NAME, admin.flattenToString());
            }
            out.attribute(null, ATTR_USER_RESTRICTIONS_MIGRATED,
                    String.valueOf(userRestrictionsMigrated));
            if (remoteBugreportUri != null) {
                out.attribute(null, ATTR_REMOTE_BUGREPORT_URI, remoteBugreportUri);
            }
            if (remoteBugreportHash != null) {
                out.attribute(null, ATTR_REMOTE_BUGREPORT_HASH, remoteBugreportHash);
            }
            if (canAccessDeviceIds) {
                out.attribute(null, ATTR_CAN_ACCESS_DEVICE_IDS,
                        String.valueOf(canAccessDeviceIds));
            }
            out.endTag(null, tag);
        }

        public static OwnerInfo readFromXml(XmlPullParser parser) {
            final String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
            final String name = parser.getAttributeValue(null, ATTR_NAME);
            final String componentName =
                    parser.getAttributeValue(null, ATTR_COMPONENT_NAME);
            final String userRestrictionsMigratedStr =
                    parser.getAttributeValue(null, ATTR_USER_RESTRICTIONS_MIGRATED);
            final boolean userRestrictionsMigrated =
                    ("true".equals(userRestrictionsMigratedStr));
            final String remoteBugreportUri = parser.getAttributeValue(null,
                    ATTR_REMOTE_BUGREPORT_URI);
            final String remoteBugreportHash = parser.getAttributeValue(null,
                    ATTR_REMOTE_BUGREPORT_HASH);
            final String canAccessDeviceIdsStr =
                    parser.getAttributeValue(null, ATTR_CAN_ACCESS_DEVICE_IDS);
            final boolean canAccessDeviceIds =
                    ("true".equals(canAccessDeviceIdsStr));

            // Has component name?  If so, return [name, component]
            if (componentName != null) {
                final ComponentName admin = ComponentName.unflattenFromString(componentName);
                if (admin != null) {
                    return new OwnerInfo(name, admin, userRestrictionsMigrated,
                            remoteBugreportUri, remoteBugreportHash, canAccessDeviceIds);
                } else {
                    // This shouldn't happen but switch from package name -> component name
                    // might have written bad device owner files. b/17652534
                    Slog.e(TAG, "Error parsing owner file. Bad component name " +
                            componentName);
                }
            }

            // Else, build with [name, package]
            return new OwnerInfo(name, packageName, userRestrictionsMigrated, remoteBugreportUri,
                    remoteBugreportHash, canAccessDeviceIds);
        }

        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + "admin=" + admin);
            pw.println(prefix + "name=" + name);
            pw.println(prefix + "package=" + packageName);
            pw.println(prefix + "canAccessDeviceIds=" + canAccessDeviceIds);
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        boolean needBlank = false;
        if (mDeviceOwner != null) {
            pw.println(prefix + "Device Owner: ");
            mDeviceOwner.dump(prefix + "  ", pw);
            pw.println(prefix + "  User ID: " + mDeviceOwnerUserId);
            needBlank = true;
        }
        if (mSystemUpdatePolicy != null) {
            if (needBlank) {
                pw.println();
            }
            pw.println(prefix + "System Update Policy: " + mSystemUpdatePolicy);
            needBlank = true;
        }
        if (mProfileOwners != null) {
            for (Map.Entry<Integer, OwnerInfo> entry : mProfileOwners.entrySet()) {
                if (needBlank) {
                    pw.println();
                }
                pw.println(prefix + "Profile Owner (User " + entry.getKey() + "): ");
                entry.getValue().dump(prefix + "  ", pw);
                needBlank = true;
            }
        }
        if (mSystemUpdateInfo != null) {
            if (needBlank) {
                pw.println();
            }
            pw.println(prefix + "Pending System Update: " + mSystemUpdateInfo);
            needBlank = true;
        }
        if (mSystemUpdateFreezeStart != null || mSystemUpdateFreezeEnd != null) {
            if (needBlank) {
                pw.println();
            }
            pw.println(prefix + "System update freeze record: "
                    + getSystemUpdateFreezePeriodRecordAsString());
            needBlank = true;
        }
    }

    @VisibleForTesting
    File getLegacyConfigFile() {
        return new File(mInjector.environmentGetDataSystemDirectory(), DEVICE_OWNER_XML_LEGACY);
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
