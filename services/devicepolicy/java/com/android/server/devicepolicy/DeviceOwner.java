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

import android.app.AppGlobals;
import android.app.admin.SystemUpdatePolicy;
import android.content.ComponentName;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Stores and restores state for the Device and Profile owners. By definition there can be
 * only one device owner, but there may be a profile owner for each user.
 */
class DeviceOwner {
    private static final String TAG = "DevicePolicyManagerService";

    private static final String DEVICE_OWNER_XML = "device_owner.xml";
    private static final String TAG_DEVICE_OWNER = "device-owner";
    private static final String TAG_DEVICE_INITIALIZER = "device-initializer";
    private static final String TAG_PROFILE_OWNER = "profile-owner";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String ATTR_COMPONENT_NAME = "component";
    private static final String ATTR_USERID = "userId";
    private static final String TAG_SYSTEM_UPDATE_POLICY = "system-update-policy";

    private AtomicFile fileForWriting;

    // Input/Output streams for testing.
    private InputStream mInputStreamForTest;
    private OutputStream mOutputStreamForTest;

    // Internal state for the device owner package.
    private OwnerInfo mDeviceOwner;

    // Internal state for the device initializer package.
    private OwnerInfo mDeviceInitializer;

    // Internal state for the profile owner packages.
    private final HashMap<Integer, OwnerInfo> mProfileOwners = new HashMap<Integer, OwnerInfo>();

    // Local system update policy controllable by device owner.
    private SystemUpdatePolicy mSystemUpdatePolicy;

    // Private default constructor.
    private DeviceOwner() {
    }

    @VisibleForTesting
    DeviceOwner(InputStream in, OutputStream out) {
        mInputStreamForTest = in;
        mOutputStreamForTest = out;
    }

    /**
     * Loads the device owner state from disk.
     */
    static DeviceOwner load() {
        DeviceOwner owner = new DeviceOwner();
        if (new File(Environment.getSystemSecureDirectory(), DEVICE_OWNER_XML).exists()) {
            owner.readOwnerFile();
            return owner;
        } else {
            return null;
        }
    }

    /**
     * Creates an instance of the device owner object with the device owner set.
     */
    static DeviceOwner createWithDeviceOwner(String packageName, String ownerName) {
        DeviceOwner owner = new DeviceOwner();
        owner.mDeviceOwner = new OwnerInfo(ownerName, packageName);
        return owner;
    }

    /**
     * Creates an instance of the device owner object with the device initializer set.
     */
    static DeviceOwner createWithDeviceInitializer(ComponentName admin) {
        DeviceOwner owner = new DeviceOwner();
        owner.mDeviceInitializer = new OwnerInfo(null, admin);
        return owner;
    }

    /**
     * Creates an instance of the device owner object with the profile owner set.
     */
    static DeviceOwner createWithProfileOwner(ComponentName admin, String ownerName, int userId) {
        DeviceOwner owner = new DeviceOwner();
        owner.mProfileOwners.put(userId, new OwnerInfo(ownerName, admin));
        return owner;
    }

    String getDeviceOwnerPackageName() {
        return mDeviceOwner != null ? mDeviceOwner.packageName : null;
    }

    String getDeviceOwnerName() {
        return mDeviceOwner != null ? mDeviceOwner.name : null;
    }

    void setDeviceOwner(String packageName, String ownerName) {
        mDeviceOwner = new OwnerInfo(ownerName, packageName);
    }

    void clearDeviceOwner() {
        mDeviceOwner = null;
    }

    ComponentName getDeviceInitializerComponent() {
        return mDeviceInitializer.admin;
    }

    String getDeviceInitializerPackageName() {
        return mDeviceInitializer != null ? mDeviceInitializer.packageName : null;
    }

    void setDeviceInitializer(ComponentName admin) {
        mDeviceInitializer = new OwnerInfo(null, admin);
    }

    void clearDeviceInitializer() {
        mDeviceInitializer = null;
    }

    boolean hasDeviceInitializer() {
        return mDeviceInitializer != null;
    }

    void setProfileOwner(ComponentName admin, String ownerName, int userId) {
        mProfileOwners.put(userId, new OwnerInfo(ownerName, admin));
    }

    void removeProfileOwner(int userId) {
        mProfileOwners.remove(userId);
    }

    ComponentName getProfileOwnerComponent(int userId) {
        OwnerInfo profileOwner = mProfileOwners.get(userId);
        return profileOwner != null ? profileOwner.admin : null;
    }

    String getProfileOwnerName(int userId) {
        OwnerInfo profileOwner = mProfileOwners.get(userId);
        return profileOwner != null ? profileOwner.name : null;
    }

    Set<Integer> getProfileOwnerKeys() {
        return mProfileOwners.keySet();
    }

    SystemUpdatePolicy getSystemUpdatePolicy() {
        return mSystemUpdatePolicy;
    }

    void setSystemUpdatePolicy(SystemUpdatePolicy systemUpdatePolicy) {
        mSystemUpdatePolicy = systemUpdatePolicy;
    }

    void clearSystemUpdatePolicy() {
        mSystemUpdatePolicy = null;
    }

    boolean hasDeviceOwner() {
        return mDeviceOwner != null;
    }

    static boolean isInstalled(String packageName, PackageManager pm) {
        try {
            PackageInfo pi;
            if ((pi = pm.getPackageInfo(packageName, 0)) != null) {
                if ((pi.applicationInfo.flags) != 0) {
                    return true;
                }
            }
        } catch (NameNotFoundException nnfe) {
            Slog.w(TAG, "Device Owner package " + packageName + " not installed.");
        }
        return false;
    }

    static boolean isInstalledForUser(String packageName, int userHandle) {
        try {
            PackageInfo pi = (AppGlobals.getPackageManager())
                    .getPackageInfo(packageName, 0, userHandle);
            if (pi != null && pi.applicationInfo.flags != 0) {
                return true;
            }
        } catch (RemoteException re) {
            throw new RuntimeException("Package manager has died", re);
        }

        return false;
    }

    @VisibleForTesting
    void readOwnerFile() {
        try {
            InputStream input = openRead();
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
                    mDeviceOwner = new OwnerInfo(name, packageName);
                } else if (tag.equals(TAG_DEVICE_INITIALIZER)) {
                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
                    String initializerComponentStr =
                            parser.getAttributeValue(null, ATTR_COMPONENT_NAME);
                    ComponentName admin =
                            ComponentName.unflattenFromString(initializerComponentStr);
                    if (admin != null) {
                        mDeviceInitializer = new OwnerInfo(null, admin);
                    } else {
                        mDeviceInitializer = new OwnerInfo(null, packageName);
                        Slog.e(TAG, "Error parsing device-owner file. Bad component name " +
                                initializerComponentStr);
                    }
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
                            profileOwnerInfo = new OwnerInfo(profileOwnerName, admin);
                        } else {
                            // This shouldn't happen but switch from package name -> component name
                            // might have written bad device owner files. b/17652534
                            Slog.e(TAG, "Error parsing device-owner file. Bad component name " +
                                    profileOwnerComponentStr);
                        }
                    }
                    if (profileOwnerInfo == null) {
                        profileOwnerInfo = new OwnerInfo(profileOwnerName, profileOwnerPackageName);
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
        } catch (XmlPullParserException xppe) {
            Slog.e(TAG, "Error parsing device-owner file\n" + xppe);
        } catch (IOException ioe) {
            Slog.e(TAG, "IO Exception when reading device-owner file\n" + ioe);
        }
    }

    @VisibleForTesting
    void writeOwnerFile() {
        synchronized (this) {
            writeOwnerFileLocked();
        }
    }

    private void writeOwnerFileLocked() {
        try {
            OutputStream outputStream = startWrite();
            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(outputStream, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            // Write device owner tag
            if (mDeviceOwner != null) {
                out.startTag(null, TAG_DEVICE_OWNER);
                out.attribute(null, ATTR_PACKAGE, mDeviceOwner.packageName);
                if (mDeviceOwner.name != null) {
                    out.attribute(null, ATTR_NAME, mDeviceOwner.name);
                }
                out.endTag(null, TAG_DEVICE_OWNER);
            }

            // Write device initializer tag
            if (mDeviceInitializer != null) {
                out.startTag(null, TAG_DEVICE_INITIALIZER);
                out.attribute(null, ATTR_PACKAGE, mDeviceInitializer.packageName);
                if (mDeviceInitializer.admin != null) {
                    out.attribute(
                            null, ATTR_COMPONENT_NAME, mDeviceInitializer.admin.flattenToString());
                }
                out.endTag(null, TAG_DEVICE_INITIALIZER);
            }

            // Write profile owner tags
            if (mProfileOwners.size() > 0) {
                for (HashMap.Entry<Integer, OwnerInfo> owner : mProfileOwners.entrySet()) {
                    out.startTag(null, TAG_PROFILE_OWNER);
                    OwnerInfo ownerInfo = owner.getValue();
                    out.attribute(null, ATTR_PACKAGE, ownerInfo.packageName);
                    out.attribute(null, ATTR_NAME, ownerInfo.name);
                    out.attribute(null, ATTR_USERID, Integer.toString(owner.getKey()));
                    if (ownerInfo.admin != null) {
                        out.attribute(null, ATTR_COMPONENT_NAME, ownerInfo.admin.flattenToString());
                    }
                    out.endTag(null, TAG_PROFILE_OWNER);
                }
            }

            // Write system update policy tag
            if (mSystemUpdatePolicy != null) {
                out.startTag(null, TAG_SYSTEM_UPDATE_POLICY);
                mSystemUpdatePolicy.saveToXml(out);
                out.endTag(null, TAG_SYSTEM_UPDATE_POLICY);
            }
            out.endDocument();
            out.flush();
            finishWrite(outputStream);
        } catch (IOException ioe) {
            Slog.e(TAG, "IO Exception when writing device-owner file\n" + ioe);
        }
    }

    private InputStream openRead() throws IOException {
        if (mInputStreamForTest != null) {
            return mInputStreamForTest;
        }

        return new AtomicFile(new File(Environment.getSystemSecureDirectory(),
                DEVICE_OWNER_XML)).openRead();
    }

    private OutputStream startWrite() throws IOException {
        if (mOutputStreamForTest != null) {
            return mOutputStreamForTest;
        }

        fileForWriting = new AtomicFile(new File(Environment.getSystemSecureDirectory(),
                DEVICE_OWNER_XML));
        return fileForWriting.startWrite();
    }

    private void finishWrite(OutputStream stream) {
        if (fileForWriting != null) {
            fileForWriting.finishWrite((FileOutputStream) stream);
        }
    }

    private static class OwnerInfo {
        public final String name;
        public final String packageName;
        public final ComponentName admin;

        public OwnerInfo(String name, String packageName) {
            this.name = name;
            this.packageName = packageName;
            this.admin = new ComponentName(packageName, "");
        }

        public OwnerInfo(String name, ComponentName admin) {
            this.name = name;
            this.admin = admin;
            this.packageName = admin.getPackageName();
        }
        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + "admin=" + admin);
            pw.println(prefix + "name=" + name);
            pw.println();
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        if (mDeviceOwner != null) {
            pw.println(prefix + "Device Owner: ");
            mDeviceOwner.dump(prefix + "  ", pw);
        }
        if (mProfileOwners != null) {
            for (Map.Entry<Integer, OwnerInfo> entry : mProfileOwners.entrySet()) {
                pw.println(prefix + "Profile Owner (User " + entry.getKey() + "): ");
                entry.getValue().dump(prefix + "  ", pw);
            }
        }
    }
}
