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
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
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
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import libcore.io.IoUtils;

/**
 * Stores and restores state for the Device and Profile owners. By definition there can be
 * only one device owner, but there may be a profile owner for each user.
 *
 * <p>This class is thread safe, so individual methods can safely be called without locking.
 * However, caller must still synchronize on their side to ensure integrity between multiple calls.
 */
class Owners {
    private static final String TAG = "DevicePolicyManagerService";

    private static final boolean DEBUG = false; // DO NOT SUBMIT WITH TRUE

    private static final String DEVICE_OWNER_XML_LEGACY = "device_owner.xml";

    private static final String DEVICE_OWNER_XML = "device_owner_2.xml";

    private static final String PROFILE_OWNER_XML = "profile_owner.xml";

    private static final String TAG_ROOT = "root";

    private static final String TAG_DEVICE_OWNER = "device-owner";
    private static final String TAG_DEVICE_INITIALIZER = "device-initializer";
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

    private static final String TAG_SYSTEM_UPDATE_POLICY = "system-update-policy";

    private final UserManager mUserManager;
    private final UserManagerInternal mUserManagerInternal;
    private final PackageManagerInternal mPackageManagerInternal;

    // Internal state for the device owner package.
    private OwnerInfo mDeviceOwner;

    private int mDeviceOwnerUserId = UserHandle.USER_NULL;

    // Internal state for the profile owner packages.
    private final ArrayMap<Integer, OwnerInfo> mProfileOwners = new ArrayMap<>();

    // Local system update policy controllable by device owner.
    private SystemUpdatePolicy mSystemUpdatePolicy;

    private final Object mLock = new Object();

    public Owners(UserManager userManager,
            UserManagerInternal userManagerInternal,
            PackageManagerInternal packageManagerInternal) {
        mUserManager = userManager;
        mUserManagerInternal = userManagerInternal;
        mPackageManagerInternal = packageManagerInternal;
    }

    /**
     * Load configuration from the disk.
     */
    void load() {
        synchronized (mLock) {
            // First, try to read from the legacy file.
            final File legacy = getLegacyConfigFileWithTestOverride();

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
            mDeviceOwner = new OwnerInfo(ownerName, admin, userRestrictionsMigrated,
                    /* remoteBugreportUri =*/ null, /* remoteBugreportHash =*/ null);
            mDeviceOwnerUserId = userId;

            mUserManagerInternal.setDeviceManaged(true);
            pushToPackageManagerLocked();
        }
    }

    void clearDeviceOwner() {
        synchronized (mLock) {
            mDeviceOwner = null;
            mDeviceOwnerUserId = UserHandle.USER_NULL;

            mUserManagerInternal.setDeviceManaged(false);
            pushToPackageManagerLocked();
        }
    }

    void setProfileOwner(ComponentName admin, String ownerName, int userId) {
        synchronized (mLock) {
            // For a newly set PO, there's no need for migration.
            mProfileOwners.put(userId, new OwnerInfo(ownerName, admin,
                    /* userRestrictionsMigrated =*/ true, /* remoteBugreportUri =*/ null,
                    /* remoteBugreportHash =*/ null));
            mUserManagerInternal.setUserManaged(userId, true);
            pushToPackageManagerLocked();
        }
    }

    void removeProfileOwner(int userId) {
        synchronized (mLock) {
            mProfileOwners.remove(userId);
            mUserManagerInternal.setUserManaged(userId, false);
            pushToPackageManagerLocked();
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
                            /* remoteBugreportHash =*/ null);
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
                                /* userRestrictionsMigrated =*/ false, null, null);
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
                                /* remoteBugreportUri =*/ null, /* remoteBugreportHash =*/ null);
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
                Slog.e(TAG, "Error parsing device-owner file", e);
            } finally {
                IoUtils.closeQuietly(input);
            }
        }

        abstract void writeInner(XmlSerializer out) throws IOException;

        abstract boolean readInner(XmlPullParser parser, int depth, String tag);
    }

    private class DeviceOwnerReadWriter extends FileReadWriter {

        protected DeviceOwnerReadWriter() {
            super(getDeviceOwnerFileWithTestOverride());
        }

        @Override
        boolean shouldWrite() {
            return (mDeviceOwner != null) || (mSystemUpdatePolicy != null);
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
            super(getProfileOwnerFileWithTestOverride(userId));
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

        public OwnerInfo(String name, String packageName, boolean userRestrictionsMigrated,
                String remoteBugreportUri, String remoteBugreportHash) {
            this.name = name;
            this.packageName = packageName;
            this.admin = new ComponentName(packageName, "");
            this.userRestrictionsMigrated = userRestrictionsMigrated;
            this.remoteBugreportUri = remoteBugreportUri;
            this.remoteBugreportHash = remoteBugreportHash;
        }

        public OwnerInfo(String name, ComponentName admin, boolean userRestrictionsMigrated,
                String remoteBugreportUri, String remoteBugreportHash) {
            this.name = name;
            this.admin = admin;
            this.packageName = admin.getPackageName();
            this.userRestrictionsMigrated = userRestrictionsMigrated;
            this.remoteBugreportUri = remoteBugreportUri;
            this.remoteBugreportHash = remoteBugreportHash;
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

            // Has component name?  If so, return [name, component]
            if (componentName != null) {
                final ComponentName admin = ComponentName.unflattenFromString(componentName);
                if (admin != null) {
                    return new OwnerInfo(name, admin, userRestrictionsMigrated,
                            remoteBugreportUri, remoteBugreportHash);
                } else {
                    // This shouldn't happen but switch from package name -> component name
                    // might have written bad device owner files. b/17652534
                    Slog.e(TAG, "Error parsing owner file. Bad component name " +
                            componentName);
                }
            }

            // Else, build with [name, package]
            return new OwnerInfo(name, packageName, userRestrictionsMigrated, remoteBugreportUri,
                    remoteBugreportHash);
        }

        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + "admin=" + admin);
            pw.println(prefix + "name=" + name);
            pw.println(prefix + "package=" + packageName);
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
                needBlank = false;
                pw.println();
            }
            pw.println(prefix + "System Update Policy: " + mSystemUpdatePolicy);
            needBlank = true;
        }
        if (mProfileOwners != null) {
            for (Map.Entry<Integer, OwnerInfo> entry : mProfileOwners.entrySet()) {
                if (needBlank) {
                    needBlank = false;
                    pw.println();
                }
                pw.println(prefix + "Profile Owner (User " + entry.getKey() + "): ");
                entry.getValue().dump(prefix + "  ", pw);
                needBlank = true;
            }
        }
    }

    File getLegacyConfigFileWithTestOverride() {
        return new File(Environment.getDataSystemDirectory(), DEVICE_OWNER_XML_LEGACY);
    }

    File getDeviceOwnerFileWithTestOverride() {
        return new File(Environment.getDataSystemDirectory(), DEVICE_OWNER_XML);
    }

    File getProfileOwnerFileWithTestOverride(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), PROFILE_OWNER_XML);
    }
}
