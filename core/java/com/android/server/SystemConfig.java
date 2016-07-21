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

package com.android.server;

import static com.android.internal.util.ArrayUtils.appendInt;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.os.storage.StorageManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads global system configuration info.
 */
public class SystemConfig {
    static final String TAG = "SystemConfig";

    static SystemConfig sInstance;

    // permission flag, determines which types of configuration are allowed to be read
    private static final int ALLOW_FEATURES = 0x01;
    private static final int ALLOW_LIBS = 0x02;
    private static final int ALLOW_PERMISSIONS = 0x04;
    private static final int ALLOW_APP_CONFIGS = 0x08;
    private static final int ALLOW_ALL = ~0;

    // Group-ids that are given to all packages as read from etc/permissions/*.xml.
    int[] mGlobalGids;

    // These are the built-in uid -> permission mappings that were read from the
    // system configuration files.
    final SparseArray<ArraySet<String>> mSystemPermissions = new SparseArray<>();

    // These are the built-in shared libraries that were read from the
    // system configuration files.  Keys are the library names; strings are the
    // paths to the libraries.
    final ArrayMap<String, String> mSharedLibraries  = new ArrayMap<>();

    // These are the features this devices supports that were read from the
    // system configuration files.
    final ArrayMap<String, FeatureInfo> mAvailableFeatures = new ArrayMap<>();

    // These are the features which this device doesn't support; the OEM
    // partition uses these to opt-out of features from the system image.
    final ArraySet<String> mUnavailableFeatures = new ArraySet<>();

    public static final class PermissionEntry {
        public final String name;
        public int[] gids;
        public boolean perUser;

        PermissionEntry(String name, boolean perUser) {
            this.name = name;
            this.perUser = perUser;
        }
    }

    // These are the permission -> gid mappings that were read from the
    // system configuration files.
    final ArrayMap<String, PermissionEntry> mPermissions = new ArrayMap<>();

    // These are the packages that are white-listed to be able to run in the
    // background while in power save mode (but not whitelisted from device idle modes),
    // as read from the configuration files.
    final ArraySet<String> mAllowInPowerSaveExceptIdle = new ArraySet<>();

    // These are the packages that are white-listed to be able to run in the
    // background while in power save mode, as read from the configuration files.
    final ArraySet<String> mAllowInPowerSave = new ArraySet<>();

    // These are the packages that are white-listed to be able to run in the
    // background while in data-usage save mode, as read from the configuration files.
    final ArraySet<String> mAllowInDataUsageSave = new ArraySet<>();

    // These are the package names of apps which should be in the 'always'
    // URL-handling state upon factory reset.
    final ArraySet<String> mLinkedApps = new ArraySet<>();

    // These are the packages that are whitelisted to be able to run as system user
    final ArraySet<String> mSystemUserWhitelistedApps = new ArraySet<>();

    // These are the packages that should not run under system user
    final ArraySet<String> mSystemUserBlacklistedApps = new ArraySet<>();

    // These are the components that are enabled by default as VR mode listener services.
    final ArraySet<ComponentName> mDefaultVrComponents = new ArraySet<>();

    // These are the permitted backup transport service components
    final ArraySet<ComponentName> mBackupTransportWhitelist = new ArraySet<>();

    // These are the packages of carrier-associated apps which should be disabled until used until
    // a SIM is inserted which grants carrier privileges to that carrier app.
    final ArrayMap<String, List<String>> mDisabledUntilUsedPreinstalledCarrierAssociatedApps =
            new ArrayMap<>();

    public static SystemConfig getInstance() {
        synchronized (SystemConfig.class) {
            if (sInstance == null) {
                sInstance = new SystemConfig();
            }
            return sInstance;
        }
    }

    public int[] getGlobalGids() {
        return mGlobalGids;
    }

    public SparseArray<ArraySet<String>> getSystemPermissions() {
        return mSystemPermissions;
    }

    public ArrayMap<String, String> getSharedLibraries() {
        return mSharedLibraries;
    }

    public ArrayMap<String, FeatureInfo> getAvailableFeatures() {
        return mAvailableFeatures;
    }

    public ArrayMap<String, PermissionEntry> getPermissions() {
        return mPermissions;
    }

    public ArraySet<String> getAllowInPowerSaveExceptIdle() {
        return mAllowInPowerSaveExceptIdle;
    }

    public ArraySet<String> getAllowInPowerSave() {
        return mAllowInPowerSave;
    }

    public ArraySet<String> getAllowInDataUsageSave() {
        return mAllowInDataUsageSave;
    }

    public ArraySet<String> getLinkedApps() {
        return mLinkedApps;
    }

    public ArraySet<String> getSystemUserWhitelistedApps() {
        return mSystemUserWhitelistedApps;
    }

    public ArraySet<String> getSystemUserBlacklistedApps() {
        return mSystemUserBlacklistedApps;
    }

    public ArraySet<ComponentName> getDefaultVrComponents() {
        return mDefaultVrComponents;
    }

    public ArraySet<ComponentName> getBackupTransportWhitelist() {
        return mBackupTransportWhitelist;
    }

    public ArrayMap<String, List<String>> getDisabledUntilUsedPreinstalledCarrierAssociatedApps() {
        return mDisabledUntilUsedPreinstalledCarrierAssociatedApps;
    }

    SystemConfig() {
        // Read configuration from system
        readPermissions(Environment.buildPath(
                Environment.getRootDirectory(), "etc", "sysconfig"), ALLOW_ALL);
        // Read configuration from the old permissions dir
        readPermissions(Environment.buildPath(
                Environment.getRootDirectory(), "etc", "permissions"), ALLOW_ALL);
        // Allow ODM to customize system configs around libs, features and apps
        int odmPermissionFlag = ALLOW_LIBS | ALLOW_FEATURES | ALLOW_APP_CONFIGS;
        readPermissions(Environment.buildPath(
                Environment.getOdmDirectory(), "etc", "sysconfig"), odmPermissionFlag);
        readPermissions(Environment.buildPath(
                Environment.getOdmDirectory(), "etc", "permissions"), odmPermissionFlag);
        // Only allow OEM to customize features
        readPermissions(Environment.buildPath(
                Environment.getOemDirectory(), "etc", "sysconfig"), ALLOW_FEATURES);
        readPermissions(Environment.buildPath(
                Environment.getOemDirectory(), "etc", "permissions"), ALLOW_FEATURES);
    }

    void readPermissions(File libraryDir, int permissionFlag) {
        // Read permissions from given directory.
        if (!libraryDir.exists() || !libraryDir.isDirectory()) {
            if (permissionFlag == ALLOW_ALL) {
                Slog.w(TAG, "No directory " + libraryDir + ", skipping");
            }
            return;
        }
        if (!libraryDir.canRead()) {
            Slog.w(TAG, "Directory " + libraryDir + " cannot be read");
            return;
        }

        // Iterate over the files in the directory and scan .xml files
        File platformFile = null;
        for (File f : libraryDir.listFiles()) {
            // We'll read platform.xml last
            if (f.getPath().endsWith("etc/permissions/platform.xml")) {
                platformFile = f;
                continue;
            }

            if (!f.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + f + " in " + libraryDir + " directory, ignoring");
                continue;
            }
            if (!f.canRead()) {
                Slog.w(TAG, "Permissions library file " + f + " cannot be read");
                continue;
            }

            readPermissionsFromXml(f, permissionFlag);
        }

        // Read platform permissions last so it will take precedence
        if (platformFile != null) {
            readPermissionsFromXml(platformFile, permissionFlag);
        }
    }

    private void readPermissionsFromXml(File permFile, int permissionFlag) {
        FileReader permReader = null;
        try {
            permReader = new FileReader(permFile);
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Couldn't find or open permissions file " + permFile);
            return;
        }

        final boolean lowRam = ActivityManager.isLowRamDeviceStatic();

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(permReader);

            int type;
            while ((type=parser.next()) != parser.START_TAG
                       && type != parser.END_DOCUMENT) {
                ;
            }

            if (type != parser.START_TAG) {
                throw new XmlPullParserException("No start tag found");
            }

            if (!parser.getName().equals("permissions") && !parser.getName().equals("config")) {
                throw new XmlPullParserException("Unexpected start tag in " + permFile
                        + ": found " + parser.getName() + ", expected 'permissions' or 'config'");
            }

            boolean allowAll = permissionFlag == ALLOW_ALL;
            boolean allowLibs = (permissionFlag & ALLOW_LIBS) != 0;
            boolean allowFeatures = (permissionFlag & ALLOW_FEATURES) != 0;
            boolean allowPermissions = (permissionFlag & ALLOW_PERMISSIONS) != 0;
            boolean allowAppConfigs = (permissionFlag & ALLOW_APP_CONFIGS) != 0;
            while (true) {
                XmlUtils.nextElement(parser);
                if (parser.getEventType() == XmlPullParser.END_DOCUMENT) {
                    break;
                }

                String name = parser.getName();
                if ("group".equals(name) && allowAll) {
                    String gidStr = parser.getAttributeValue(null, "gid");
                    if (gidStr != null) {
                        int gid = android.os.Process.getGidForName(gidStr);
                        mGlobalGids = appendInt(mGlobalGids, gid);
                    } else {
                        Slog.w(TAG, "<group> without gid in " + permFile + " at "
                                + parser.getPositionDescription());
                    }

                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else if ("permission".equals(name) && allowPermissions) {
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Slog.w(TAG, "<permission> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    perm = perm.intern();
                    readPermission(parser, perm);

                } else if ("assign-permission".equals(name) && allowPermissions) {
                    String perm = parser.getAttributeValue(null, "name");
                    if (perm == null) {
                        Slog.w(TAG, "<assign-permission> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    String uidStr = parser.getAttributeValue(null, "uid");
                    if (uidStr == null) {
                        Slog.w(TAG, "<assign-permission> without uid in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    int uid = Process.getUidForName(uidStr);
                    if (uid < 0) {
                        Slog.w(TAG, "<assign-permission> with unknown uid \""
                                + uidStr + "  in " + permFile + " at "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    perm = perm.intern();
                    ArraySet<String> perms = mSystemPermissions.get(uid);
                    if (perms == null) {
                        perms = new ArraySet<String>();
                        mSystemPermissions.put(uid, perms);
                    }
                    perms.add(perm);
                    XmlUtils.skipCurrentTag(parser);

                } else if ("library".equals(name) && allowLibs) {
                    String lname = parser.getAttributeValue(null, "name");
                    String lfile = parser.getAttributeValue(null, "file");
                    if (lname == null) {
                        Slog.w(TAG, "<library> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else if (lfile == null) {
                        Slog.w(TAG, "<library> without file in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        //Log.i(TAG, "Got library " + lname + " in " + lfile);
                        mSharedLibraries.put(lname, lfile);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("feature".equals(name) && allowFeatures) {
                    String fname = parser.getAttributeValue(null, "name");
                    int fversion = XmlUtils.readIntAttribute(parser, "version", 0);
                    boolean allowed;
                    if (!lowRam) {
                        allowed = true;
                    } else {
                        String notLowRam = parser.getAttributeValue(null, "notLowRam");
                        allowed = !"true".equals(notLowRam);
                    }
                    if (fname == null) {
                        Slog.w(TAG, "<feature> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else if (allowed) {
                        addFeature(fname, fversion);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("unavailable-feature".equals(name) && allowFeatures) {
                    String fname = parser.getAttributeValue(null, "name");
                    if (fname == null) {
                        Slog.w(TAG, "<unavailable-feature> without name in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        mUnavailableFeatures.add(fname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-in-power-save-except-idle".equals(name) && allowAll) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-in-power-save-except-idle> without package in "
                                + permFile + " at " + parser.getPositionDescription());
                    } else {
                        mAllowInPowerSaveExceptIdle.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-in-power-save".equals(name) && allowAll) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-in-power-save> without package in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        mAllowInPowerSave.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("allow-in-data-usage-save".equals(name) && allowAll) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<allow-in-data-usage-save> without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mAllowInDataUsageSave.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;

                } else if ("app-link".equals(name) && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<app-link> without package in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        mLinkedApps.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("system-user-whitelisted-app".equals(name) && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<system-user-whitelisted-app> without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mSystemUserWhitelistedApps.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("system-user-blacklisted-app".equals(name) && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    if (pkgname == null) {
                        Slog.w(TAG, "<system-user-blacklisted-app without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mSystemUserBlacklistedApps.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("default-enabled-vr-app".equals(name) && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    String clsname = parser.getAttributeValue(null, "class");
                    if (pkgname == null) {
                        Slog.w(TAG, "<default-enabled-vr-app without package in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else if (clsname == null) {
                        Slog.w(TAG, "<default-enabled-vr-app without class in " + permFile
                                + " at " + parser.getPositionDescription());
                    } else {
                        mDefaultVrComponents.add(new ComponentName(pkgname, clsname));
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("backup-transport-whitelisted-service".equals(name) && allowFeatures) {
                    String serviceName = parser.getAttributeValue(null, "service");
                    if (serviceName == null) {
                        Slog.w(TAG, "<backup-transport-whitelisted-service> without service in "
                                + permFile + " at " + parser.getPositionDescription());
                    } else {
                        ComponentName cn = ComponentName.unflattenFromString(serviceName);
                        if (cn == null) {
                            Slog.w(TAG,
                                    "<backup-transport-whitelisted-service> with invalid service name "
                                    + serviceName + " in "+ permFile
                                    + " at " + parser.getPositionDescription());
                        } else {
                            mBackupTransportWhitelist.add(cn);
                        }
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else if ("disabled-until-used-preinstalled-carrier-associated-app".equals(name)
                        && allowAppConfigs) {
                    String pkgname = parser.getAttributeValue(null, "package");
                    String carrierPkgname = parser.getAttributeValue(null, "carrierAppPackage");
                    if (pkgname == null || carrierPkgname == null) {
                        Slog.w(TAG, "<disabled-until-used-preinstalled-carrier-associated-app"
                                + " without package or carrierAppPackage in " + permFile + " at "
                                + parser.getPositionDescription());
                    } else {
                        List<String> associatedPkgs =
                                mDisabledUntilUsedPreinstalledCarrierAssociatedApps.get(
                                        carrierPkgname);
                        if (associatedPkgs == null) {
                            associatedPkgs = new ArrayList<>();
                            mDisabledUntilUsedPreinstalledCarrierAssociatedApps.put(
                                    carrierPkgname, associatedPkgs);
                        }
                        associatedPkgs.add(pkgname);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
            }
        } catch (XmlPullParserException e) {
            Slog.w(TAG, "Got exception parsing permissions.", e);
        } catch (IOException e) {
            Slog.w(TAG, "Got exception parsing permissions.", e);
        } finally {
            IoUtils.closeQuietly(permReader);
        }

        // Some devices can be field-converted to FBE, so offer to splice in
        // those features if not already defined by the static config
        if (StorageManager.isFileEncryptedNativeOnly()) {
            addFeature(PackageManager.FEATURE_FILE_BASED_ENCRYPTION, 0);
            addFeature(PackageManager.FEATURE_SECURELY_REMOVES_USERS, 0);
        }

        for (String featureName : mUnavailableFeatures) {
            removeFeature(featureName);
        }
    }

    private void addFeature(String name, int version) {
        FeatureInfo fi = mAvailableFeatures.get(name);
        if (fi == null) {
            fi = new FeatureInfo();
            fi.name = name;
            fi.version = version;
            mAvailableFeatures.put(name, fi);
        } else {
            fi.version = Math.max(fi.version, version);
        }
    }

    private void removeFeature(String name) {
        if (mAvailableFeatures.remove(name) != null) {
            Slog.d(TAG, "Removed unavailable feature " + name);
        }
    }

    void readPermission(XmlPullParser parser, String name)
            throws IOException, XmlPullParserException {
        if (mPermissions.containsKey(name)) {
            throw new IllegalStateException("Duplicate permission definition for " + name);
        }

        final boolean perUser = XmlUtils.readBooleanAttribute(parser, "perUser", false);
        final PermissionEntry perm = new PermissionEntry(name, perUser);
        mPermissions.put(name, perm);

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if ("group".equals(tagName)) {
                String gidStr = parser.getAttributeValue(null, "gid");
                if (gidStr != null) {
                    int gid = Process.getGidForName(gidStr);
                    perm.gids = appendInt(perm.gids, gid);
                } else {
                    Slog.w(TAG, "<group> without gid at "
                            + parser.getPositionDescription());
                }
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }
}
