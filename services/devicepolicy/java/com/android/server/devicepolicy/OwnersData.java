/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.app.admin.SystemUpdateInfo;
import android.app.admin.SystemUpdatePolicy;
import android.app.admin.flags.Flags;
import android.content.ComponentName;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class OwnersData {
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
    private static final String TAG_POLICY_ENGINE_MIGRATION = "policy-engine-migration";

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

    private static final String ATTR_MIGRATED_TO_POLICY_ENGINE = "migratedToPolicyEngine";
    private static final String ATTR_SECURITY_LOG_MIGRATED = "securityLogMigrated";

    private static final String ATTR_MIGRATED_POST_UPGRADE = "migratedPostUpgrade";

    // Internal state for the device owner package.
    OwnerInfo mDeviceOwner;
    int mDeviceOwnerUserId = UserHandle.USER_NULL;

    // Device owner type for a managed device.
    final ArrayMap<String, Integer> mDeviceOwnerTypes = new ArrayMap<>();

    /** @deprecated moved to {@link ActiveAdmin#protectedPackages}. */
    @Deprecated
    @Nullable
    ArrayMap<String, List<String>> mDeviceOwnerProtectedPackages;

    // Internal state for the profile owner packages.
    final ArrayMap<Integer, OwnerInfo> mProfileOwners = new ArrayMap<>();

    // Local system update policy controllable by device owner.
    SystemUpdatePolicy mSystemUpdatePolicy;
    LocalDate mSystemUpdateFreezeStart;
    LocalDate mSystemUpdateFreezeEnd;

    // Pending OTA info if there is one.
    @Nullable
    SystemUpdateInfo mSystemUpdateInfo;
    private final PolicyPathProvider mPathProvider;

    boolean mMigratedToPolicyEngine = false;
    boolean mSecurityLoggingMigrated = false;

    boolean mPoliciesMigratedPostUpdate = false;

    OwnersData(PolicyPathProvider pathProvider) {
        mPathProvider = pathProvider;
    }

    void load(int[] allUsers) {
        new DeviceOwnerReadWriter().readFromFileLocked();

        for (int userId : allUsers) {
            new ProfileOwnerReadWriter(userId).readFromFileLocked();
        }

        OwnerInfo profileOwner = mProfileOwners.get(mDeviceOwnerUserId);
        ComponentName admin = profileOwner != null ? profileOwner.admin : null;
        if (mDeviceOwner != null && admin != null) {
            Slog.w(TAG, String.format("User %d has both DO and PO, which is not supported",
                    mDeviceOwnerUserId));
        }
    }

    /**
     * @return true upon success, false otherwise.
     */
    boolean writeDeviceOwner() {
        if (DEBUG) {
            Log.d(TAG, "Writing to device owner file");
        }
        return new DeviceOwnerReadWriter().writeToFileLocked();
    }

    /**
     * @return true upon success, false otherwise.
     */
    boolean writeProfileOwner(int userId) {
        if (DEBUG) {
            Log.d(TAG, "Writing to profile owner file for user " + userId);
        }
        return new ProfileOwnerReadWriter(userId).writeToFileLocked();
    }

    void dump(IndentingPrintWriter pw) {
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

    @VisibleForTesting
    File getDeviceOwnerFile() {
        return new File(mPathProvider.getDataSystemDirectory(), DEVICE_OWNER_XML);
    }

    @VisibleForTesting
    File getProfileOwnerFile(int userId) {
        return new File(mPathProvider.getUserSystemDirectory(userId), PROFILE_OWNER_XML);
    }

    private abstract static class FileReadWriter {
        private final File mFile;

        protected FileReadWriter(File file) {
            mFile = file;
        }

        abstract boolean shouldWrite();

        boolean writeToFileLocked() {
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
                return true;
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
                return false;
            }
            return true;
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
            return Flags.alwaysPersistDo()
                    || (mDeviceOwner != null) || (mSystemUpdatePolicy != null)
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
                    out.attribute(
                            null, ATTR_FREEZE_RECORD_START, mSystemUpdateFreezeStart.toString());
                }
                if (mSystemUpdateFreezeEnd != null) {
                    out.attribute(null, ATTR_FREEZE_RECORD_END, mSystemUpdateFreezeEnd.toString());
                }
                out.endTag(null, TAG_FREEZE_PERIOD_RECORD);
            }

            out.startTag(null, TAG_POLICY_ENGINE_MIGRATION);
            out.attributeBoolean(null, ATTR_MIGRATED_TO_POLICY_ENGINE, mMigratedToPolicyEngine);
            out.attributeBoolean(null, ATTR_MIGRATED_POST_UPGRADE, mPoliciesMigratedPostUpdate);
            if (Flags.securityLogV2Enabled()) {
                out.attributeBoolean(null, ATTR_SECURITY_LOG_MIGRATED, mSecurityLoggingMigrated);
            }
            out.endTag(null, TAG_POLICY_ENGINE_MIGRATION);

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
                    mDeviceOwnerUserId =
                            parser.getAttributeInt(null, ATTR_USERID, mDeviceOwnerUserId);
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
                    int deviceOwnerType = parser.getAttributeInt(
                            null, ATTR_DEVICE_OWNER_TYPE_VALUE, DEVICE_OWNER_TYPE_DEFAULT);
                    mDeviceOwnerTypes.put(packageName, deviceOwnerType);
                    break;
                // Deprecated fields below.
                case TAG_DEVICE_OWNER_PROTECTED_PACKAGES:
                    packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    int protectedPackagesSize = parser.getAttributeInt(null, ATTR_SIZE, 0);
                    List<String> protectedPackages = new ArrayList<>();
                    for (int i = 0; i < protectedPackagesSize; i++) {
                        protectedPackages.add(parser.getAttributeValue(null, ATTR_NAME + i));
                    }
                    if (mDeviceOwnerProtectedPackages == null) {
                        mDeviceOwnerProtectedPackages = new ArrayMap<>();
                    }
                    mDeviceOwnerProtectedPackages.put(packageName, protectedPackages);
                    break;
                case TAG_POLICY_ENGINE_MIGRATION:
                    mMigratedToPolicyEngine = parser.getAttributeBoolean(
                            null, ATTR_MIGRATED_TO_POLICY_ENGINE, false);
                    mPoliciesMigratedPostUpdate = parser.getAttributeBoolean(
                            null, ATTR_MIGRATED_POST_UPGRADE, false);
                    mSecurityLoggingMigrated = Flags.securityLogV2Enabled()
                            && parser.getAttributeBoolean(null, ATTR_SECURITY_LOG_MIGRATED, false);

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
        public final String packageName;
        public final ComponentName admin;
        public String remoteBugreportUri;
        public String remoteBugreportHash;
        public boolean isOrganizationOwnedDevice;

        OwnerInfo(ComponentName admin, String remoteBugreportUri,
                String remoteBugreportHash, boolean isOrganizationOwnedDevice) {
            this.admin = admin;
            this.packageName = admin.getPackageName();
            this.remoteBugreportUri = remoteBugreportUri;
            this.remoteBugreportHash = remoteBugreportHash;
            this.isOrganizationOwnedDevice = isOrganizationOwnedDevice;
        }

        public void writeToXml(TypedXmlSerializer out, String tag) throws IOException {
            out.startTag(null, tag);
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
                out.attributeBoolean(
                        null, ATTR_PROFILE_OWNER_OF_ORG_OWNED_DEVICE, isOrganizationOwnedDevice);
            }
            out.endTag(null, tag);
        }

        public static OwnerInfo readFromXml(TypedXmlPullParser parser) {
            final String componentName = parser.getAttributeValue(null, ATTR_COMPONENT_NAME);
            final String remoteBugreportUri =
                    parser.getAttributeValue(null, ATTR_REMOTE_BUGREPORT_URI);
            final String remoteBugreportHash =
                    parser.getAttributeValue(null, ATTR_REMOTE_BUGREPORT_HASH);
            final String canAccessDeviceIdsStr =
                    parser.getAttributeValue(null, ATTR_CAN_ACCESS_DEVICE_IDS);
            final boolean canAccessDeviceIds = "true".equals(canAccessDeviceIdsStr);
            final String isOrgOwnedDeviceStr =
                    parser.getAttributeValue(null, ATTR_PROFILE_OWNER_OF_ORG_OWNED_DEVICE);
            final boolean isOrgOwnedDevice =
                    "true".equals(isOrgOwnedDeviceStr) | canAccessDeviceIds;

            if (componentName == null) {
                Slog.e(TAG, "Owner component not found");
                return null;
            }
            final ComponentName admin = ComponentName.unflattenFromString(componentName);
            if (admin == null) {
                Slog.e(TAG, "Owner component not parsable: " + componentName);
                return null;
            }

            return new OwnerInfo(admin, remoteBugreportUri, remoteBugreportHash, isOrgOwnedDevice);
        }

        public void dump(IndentingPrintWriter pw) {
            pw.println("admin=" + admin);
            pw.println("package=" + packageName);
            pw.println("isOrganizationOwnedDevice=" + isOrganizationOwnedDevice);
        }
    }
}
