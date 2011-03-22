/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.server.IntentResolver;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Holds information about dynamic settings.
 */
final class Settings {
    private final File mSettingsFilename;
    private final File mBackupSettingsFilename;
    private final File mPackageListFilename;
    private final File mStoppedPackagesFilename;
    private final File mBackupStoppedPackagesFilename;
    final HashMap<String, PackageSetting> mPackages =
            new HashMap<String, PackageSetting>();
    // List of replaced system applications
    final HashMap<String, PackageSetting> mDisabledSysPackages =
        new HashMap<String, PackageSetting>();

    // These are the last platform API version we were using for
    // the apps installed on internal and external storage.  It is
    // used to grant newer permissions one time during a system upgrade.
    int mInternalSdkPlatform;
    int mExternalSdkPlatform;
    
    // The user's preferred activities associated with particular intent
    // filters.
    final IntentResolver<PreferredActivity, PreferredActivity> mPreferredActivities =
                new IntentResolver<PreferredActivity, PreferredActivity>() {
        @Override
        protected String packageForFilter(PreferredActivity filter) {
            return filter.mPref.mComponent.getPackageName();
        }
        @Override
        protected void dumpFilter(PrintWriter out, String prefix,
                PreferredActivity filter) {
            filter.mPref.dump(out, prefix, filter);
        }
    };
    final HashMap<String, SharedUserSetting> mSharedUsers =
            new HashMap<String, SharedUserSetting>();
    private final ArrayList<Object> mUserIds = new ArrayList<Object>();
    private final SparseArray<Object> mOtherUserIds =
            new SparseArray<Object>();

    // For reading/writing settings file.
    private final ArrayList<Signature> mPastSignatures =
            new ArrayList<Signature>();

    // Mapping from permission names to info about them.
    final HashMap<String, BasePermission> mPermissions =
            new HashMap<String, BasePermission>();

    // Mapping from permission tree names to info about them.
    final HashMap<String, BasePermission> mPermissionTrees =
            new HashMap<String, BasePermission>();

    // Packages that have been uninstalled and still need their external
    // storage data deleted.
    final ArrayList<String> mPackagesToBeCleaned = new ArrayList<String>();
    
    // Packages that have been renamed since they were first installed.
    // Keys are the new names of the packages, values are the original
    // names.  The packages appear everwhere else under their original
    // names.
    final HashMap<String, String> mRenamedPackages = new HashMap<String, String>();
    
    final StringBuilder mReadMessages = new StringBuilder();

    private final ArrayList<PendingPackage> mPendingPackages
            = new ArrayList<PendingPackage>();

    Settings() {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        // TODO(oam): This secure dir creation needs to be moved somewhere else (later)
        File systemSecureDir = new File(dataDir, "secure/system");
        systemDir.mkdirs();
        systemSecureDir.mkdirs();
        FileUtils.setPermissions(systemDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        FileUtils.setPermissions(systemSecureDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        mSettingsFilename = new File(systemDir, "packages.xml");
        mBackupSettingsFilename = new File(systemDir, "packages-backup.xml");
        mPackageListFilename = new File(systemDir, "packages.list");
        mStoppedPackagesFilename = new File(systemDir, "packages-stopped.xml");
        mBackupStoppedPackagesFilename = new File(systemDir, "packages-stopped-backup.xml");
    }

    PackageSetting getPackageLP(PackageParser.Package pkg, PackageSetting origPackage,
            String realName, SharedUserSetting sharedUser, File codePath, File resourcePath,
            String nativeLibraryPathString, int pkgFlags, boolean create, boolean add) {
        final String name = pkg.packageName;
        PackageSetting p = getPackageLP(name, origPackage, realName, sharedUser, codePath,
                resourcePath, nativeLibraryPathString, pkg.mVersionCode, pkgFlags, create, add);
        return p;
    }

    PackageSetting peekPackageLP(String name) {
        return mPackages.get(name);
        /*
        PackageSetting p = mPackages.get(name);
        if (p != null && p.codePath.getPath().equals(codePath)) {
            return p;
        }
        return null;
        */
    }

    void setInstallStatus(String pkgName, int status) {
        PackageSetting p = mPackages.get(pkgName);
        if(p != null) {
            if(p.getInstallStatus() != status) {
                p.setInstallStatus(status);
            }
        }
    }

    void setInstallerPackageName(String pkgName,
            String installerPkgName) {
        PackageSetting p = mPackages.get(pkgName);
        if(p != null) {
            p.setInstallerPackageName(installerPkgName);
        }
    }

    SharedUserSetting getSharedUserLP(String name,
            int pkgFlags, boolean create) {
        SharedUserSetting s = mSharedUsers.get(name);
        if (s == null) {
            if (!create) {
                return null;
            }
            s = new SharedUserSetting(name, pkgFlags);
            if (PackageManagerService.MULTIPLE_APPLICATION_UIDS) {
                s.userId = newUserIdLP(s);
            } else {
                s.userId = PackageManagerService.FIRST_APPLICATION_UID;
            }
            Log.i(PackageManagerService.TAG, "New shared user " + name + ": id=" + s.userId);
            // < 0 means we couldn't assign a userid; fall out and return
            // s, which is currently null
            if (s.userId >= 0) {
                mSharedUsers.put(name, s);
            }
        }

        return s;
    }

    boolean disableSystemPackageLP(String name) {
        PackageSetting p = mPackages.get(name);
        if(p == null) {
            Log.w(PackageManagerService.TAG, "Package:"+name+" is not an installed package");
            return false;
        }
        PackageSetting dp = mDisabledSysPackages.get(name);
        // always make sure the system package code and resource paths dont change
        if (dp == null) {
            if((p.pkg != null) && (p.pkg.applicationInfo != null)) {
                p.pkg.applicationInfo.flags |= ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
            }
            mDisabledSysPackages.put(name, p);

            // a little trick...  when we install the new package, we don't
            // want to modify the existing PackageSetting for the built-in
            // version.  so at this point we need a new PackageSetting that
            // is okay to muck with.
            PackageSetting newp = new PackageSetting(p);
            replacePackageLP(name, newp);
            return true;
        }
        return false;
    }

    PackageSetting enableSystemPackageLP(String name) {
        PackageSetting p = mDisabledSysPackages.get(name);
        if(p == null) {
            Log.w(PackageManagerService.TAG, "Package:"+name+" is not disabled");
            return null;
        }
        // Reset flag in ApplicationInfo object
        if((p.pkg != null) && (p.pkg.applicationInfo != null)) {
            p.pkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        }
        PackageSetting ret = addPackageLP(name, p.realName, p.codePath, p.resourcePath,
                p.nativeLibraryPathString, p.userId, p.versionCode, p.pkgFlags);
        mDisabledSysPackages.remove(name);
        return ret;
    }

    PackageSetting addPackageLP(String name, String realName, File codePath, File resourcePath,
            String nativeLibraryPathString, int uid, int vc, int pkgFlags) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (p.userId == uid) {
                return p;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate package, keeping first: " + name);
            return null;
        }
        p = new PackageSetting(name, realName, codePath, resourcePath, nativeLibraryPathString,
                vc, pkgFlags);
        p.userId = uid;
        if (addUserIdLP(uid, p, name)) {
            mPackages.put(name, p);
            return p;
        }
        return null;
    }

    SharedUserSetting addSharedUserLP(String name, int uid, int pkgFlags) {
        SharedUserSetting s = mSharedUsers.get(name);
        if (s != null) {
            if (s.userId == uid) {
                return s;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate shared user, keeping first: " + name);
            return null;
        }
        s = new SharedUserSetting(name, pkgFlags);
        s.userId = uid;
        if (addUserIdLP(uid, s, name)) {
            mSharedUsers.put(name, s);
            return s;
        }
        return null;
    }

    // Transfer ownership of permissions from one package to another.
    void transferPermissions(String origPkg, String newPkg) {
        // Transfer ownership of permissions to the new package.
        for (int i=0; i<2; i++) {
            HashMap<String, BasePermission> permissions =
                    i == 0 ? mPermissionTrees : mPermissions;
            for (BasePermission bp : permissions.values()) {
                if (origPkg.equals(bp.sourcePackage)) {
                    if (PackageManagerService.DEBUG_UPGRADE) Log.v(PackageManagerService.TAG,
                            "Moving permission " + bp.name
                            + " from pkg " + bp.sourcePackage
                            + " to " + newPkg);
                    bp.sourcePackage = newPkg;
                    bp.packageSetting = null;
                    bp.perm = null;
                    if (bp.pendingInfo != null) {
                        bp.pendingInfo.packageName = newPkg;
                    }
                    bp.uid = 0;
                    bp.gids = null;
                }
            }
        }
    }
    
    private PackageSetting getPackageLP(String name, PackageSetting origPackage,
            String realName, SharedUserSetting sharedUser, File codePath, File resourcePath,
            String nativeLibraryPathString, int vc, int pkgFlags, boolean create, boolean add) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (!p.codePath.equals(codePath)) {
                // Check to see if its a disabled system app
                if ((p.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // This is an updated system app with versions in both system
                    // and data partition. Just let the most recent version
                    // take precedence.
                    Slog.w(PackageManagerService.TAG, "Trying to update system app code path from " +
                            p.codePathString + " to " + codePath.toString());
                } else {
                    // Just a change in the code path is not an issue, but
                    // let's log a message about it.
                    Slog.i(PackageManagerService.TAG, "Package " + name + " codePath changed from " + p.codePath
                            + " to " + codePath + "; Retaining data and using new");
                    /*
                     * Since we've changed paths, we need to prefer the new
                     * native library path over the one stored in the
                     * package settings since we might have moved from
                     * internal to external storage or vice versa.
                     */
                    p.nativeLibraryPathString = nativeLibraryPathString;
                }
            }
            if (p.sharedUser != sharedUser) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Package " + name + " shared user changed from "
                        + (p.sharedUser != null ? p.sharedUser.name : "<nothing>")
                        + " to "
                        + (sharedUser != null ? sharedUser.name : "<nothing>")
                        + "; replacing with new");
                p = null;
            } else {
                if ((pkgFlags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // If what we are scanning is a system package, then
                    // make it so, regardless of whether it was previously
                    // installed only in the data partition.
                    p.pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                }
            }
        }
        if (p == null) {
            // Create a new PackageSettings entry. this can end up here because
            // of code path mismatch or user id mismatch of an updated system partition
            if (!create) {
                return null;
            }
            if (origPackage != null) {
                // We are consuming the data from an existing package.
                p = new PackageSetting(origPackage.name, name, codePath, resourcePath,
                        nativeLibraryPathString, vc, pkgFlags);
                if (PackageManagerService.DEBUG_UPGRADE) Log.v(PackageManagerService.TAG, "Package " + name
                        + " is adopting original package " + origPackage.name);
                // Note that we will retain the new package's signature so
                // that we can keep its data.
                PackageSignatures s = p.signatures;
                p.copyFrom(origPackage);
                p.signatures = s;
                p.sharedUser = origPackage.sharedUser;
                p.userId = origPackage.userId;
                p.origPackage = origPackage;
                mRenamedPackages.put(name, origPackage.name);
                name = origPackage.name;
                // Update new package state.
                p.setTimeStamp(codePath.lastModified());
            } else {
                p = new PackageSetting(name, realName, codePath, resourcePath,
                        nativeLibraryPathString, vc, pkgFlags);
                p.setTimeStamp(codePath.lastModified());
                p.sharedUser = sharedUser;
                // If this is not a system app, it starts out stopped.
                if ((pkgFlags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                    if (PackageManagerService.DEBUG_STOPPED) {
                        RuntimeException e = new RuntimeException("here");
                        e.fillInStackTrace();
                        Slog.i(PackageManagerService.TAG, "Stopping package " + name, e);
                    }
                    p.stopped = true;
                    p.notLaunched = true;
                }
                if (sharedUser != null) {
                    p.userId = sharedUser.userId;
                } else if (PackageManagerService.MULTIPLE_APPLICATION_UIDS) {
                    // Clone the setting here for disabled system packages
                    PackageSetting dis = mDisabledSysPackages.get(name);
                    if (dis != null) {
                        // For disabled packages a new setting is created
                        // from the existing user id. This still has to be
                        // added to list of user id's
                        // Copy signatures from previous setting
                        if (dis.signatures.mSignatures != null) {
                            p.signatures.mSignatures = dis.signatures.mSignatures.clone();
                        }
                        p.userId = dis.userId;
                        // Clone permissions
                        p.grantedPermissions = new HashSet<String>(dis.grantedPermissions);
                        // Clone component info
                        p.disabledComponents = new HashSet<String>(dis.disabledComponents);
                        p.enabledComponents = new HashSet<String>(dis.enabledComponents);
                        // Add new setting to list of user ids
                        addUserIdLP(p.userId, p, name);
                    } else {
                        // Assign new user id
                        p.userId = newUserIdLP(p);
                    }
                } else {
                    p.userId = PackageManagerService.FIRST_APPLICATION_UID;
                }
            }
            if (p.userId < 0) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Package " + name + " could not be assigned a valid uid");
                return null;
            }
            if (add) {
                // Finish adding new package by adding it and updating shared
                // user preferences
                addPackageSettingLP(p, name, sharedUser);
            }
        }
        return p;
    }

    void insertPackageSettingLP(PackageSetting p, PackageParser.Package pkg) {
        p.pkg = pkg;
        pkg.mSetEnabled = p.enabled;
        pkg.mSetStopped = p.stopped;
        final String codePath = pkg.applicationInfo.sourceDir;
        final String resourcePath = pkg.applicationInfo.publicSourceDir;
        // Update code path if needed
        if (!codePath.equalsIgnoreCase(p.codePathString)) {
            Slog.w(PackageManagerService.TAG, "Code path for pkg : " + p.pkg.packageName +
                    " changing from " + p.codePathString + " to " + codePath);
            p.codePath = new File(codePath);
            p.codePathString = codePath;
        }
        //Update resource path if needed
        if (!resourcePath.equalsIgnoreCase(p.resourcePathString)) {
            Slog.w(PackageManagerService.TAG, "Resource path for pkg : " + p.pkg.packageName +
                    " changing from " + p.resourcePathString + " to " + resourcePath);
            p.resourcePath = new File(resourcePath);
            p.resourcePathString = resourcePath;
        }
        // Update the native library path if needed
        final String nativeLibraryPath = pkg.applicationInfo.nativeLibraryDir;
        if (nativeLibraryPath != null
                && !nativeLibraryPath.equalsIgnoreCase(p.nativeLibraryPathString)) {
            p.nativeLibraryPathString = nativeLibraryPath;
        }
        // Update version code if needed
         if (pkg.mVersionCode != p.versionCode) {
            p.versionCode = pkg.mVersionCode;
        }
         // Update signatures if needed.
         if (p.signatures.mSignatures == null) {
             p.signatures.assignSignatures(pkg.mSignatures);
         }
         // If this app defines a shared user id initialize
         // the shared user signatures as well.
         if (p.sharedUser != null && p.sharedUser.signatures.mSignatures == null) {
             p.sharedUser.signatures.assignSignatures(pkg.mSignatures);
         }
        addPackageSettingLP(p, pkg.packageName, p.sharedUser);
    }

    // Utility method that adds a PackageSetting to mPackages and
    // completes updating the shared user attributes
    private void addPackageSettingLP(PackageSetting p, String name,
            SharedUserSetting sharedUser) {
        mPackages.put(name, p);
        if (sharedUser != null) {
            if (p.sharedUser != null && p.sharedUser != sharedUser) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Package " + p.name + " was user "
                        + p.sharedUser + " but is now " + sharedUser
                        + "; I am not changing its files so it will probably fail!");
                p.sharedUser.packages.remove(p);
            } else if (p.userId != sharedUser.userId) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Package " + p.name + " was user id " + p.userId
                    + " but is now user " + sharedUser
                    + " with id " + sharedUser.userId
                    + "; I am not changing its files so it will probably fail!");
            }

            sharedUser.packages.add(p);
            p.sharedUser = sharedUser;
            p.userId = sharedUser.userId;
        }
    }

    /*
     * Update the shared user setting when a package using
     * specifying the shared user id is removed. The gids
     * associated with each permission of the deleted package
     * are removed from the shared user's gid list only if its
     * not in use by other permissions of packages in the
     * shared user setting.
     */
    void updateSharedUserPermsLP(PackageSetting deletedPs, int[] globalGids) {
        if ( (deletedPs == null) || (deletedPs.pkg == null)) {
            Slog.i(PackageManagerService.TAG, "Trying to update info for null package. Just ignoring");
            return;
        }
        // No sharedUserId
        if (deletedPs.sharedUser == null) {
            return;
        }
        SharedUserSetting sus = deletedPs.sharedUser;
        // Update permissions
        for (String eachPerm: deletedPs.pkg.requestedPermissions) {
            boolean used = false;
            if (!sus.grantedPermissions.contains (eachPerm)) {
                continue;
            }
            for (PackageSetting pkg:sus.packages) {
                if (pkg.pkg != null &&
                        !pkg.pkg.packageName.equals(deletedPs.pkg.packageName) &&
                        pkg.pkg.requestedPermissions.contains(eachPerm)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                // can safely delete this permission from list
                sus.grantedPermissions.remove(eachPerm);
            }
        }
        // Update gids
        int newGids[] = globalGids;
        for (String eachPerm : sus.grantedPermissions) {
            BasePermission bp = mPermissions.get(eachPerm);
            if (bp != null) {
                newGids = PackageManagerService.appendInts(newGids, bp.gids);
            }
        }
        sus.gids = newGids;
    }

    int removePackageLP(String name) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            mPackages.remove(name);
            if (p.sharedUser != null) {
                p.sharedUser.packages.remove(p);
                if (p.sharedUser.packages.size() == 0) {
                    mSharedUsers.remove(p.sharedUser.name);
                    removeUserIdLP(p.sharedUser.userId);
                    return p.sharedUser.userId;
                }
            } else {
                removeUserIdLP(p.userId);
                return p.userId;
            }
        }
        return -1;
    }

    private void replacePackageLP(String name, PackageSetting newp) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (p.sharedUser != null) {
                p.sharedUser.packages.remove(p);
                p.sharedUser.packages.add(newp);
            } else {
                replaceUserIdLP(p.userId, newp);
            }
        }
        mPackages.put(name, newp);
    }

    private boolean addUserIdLP(int uid, Object obj, Object name) {
        if (uid >= PackageManagerService.FIRST_APPLICATION_UID + PackageManagerService.MAX_APPLICATION_UIDS) {
            return false;
        }

        if (uid >= PackageManagerService.FIRST_APPLICATION_UID) {
            int N = mUserIds.size();
            final int index = uid - PackageManagerService.FIRST_APPLICATION_UID;
            while (index >= N) {
                mUserIds.add(null);
                N++;
            }
            if (mUserIds.get(index) != null) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Adding duplicate user id: " + uid
                        + " name=" + name);
                return false;
            }
            mUserIds.set(index, obj);
        } else {
            if (mOtherUserIds.get(uid) != null) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Adding duplicate shared id: " + uid
                        + " name=" + name);
                return false;
            }
            mOtherUserIds.put(uid, obj);
        }
        return true;
    }

    public Object getUserIdLP(int uid) {
        if (uid >= PackageManagerService.FIRST_APPLICATION_UID) {
            final int N = mUserIds.size();
            final int index = uid - PackageManagerService.FIRST_APPLICATION_UID;
            return index < N ? mUserIds.get(index) : null;
        } else {
            return mOtherUserIds.get(uid);
        }
    }

    private void removeUserIdLP(int uid) {
        if (uid >= PackageManagerService.FIRST_APPLICATION_UID) {
            final int N = mUserIds.size();
            final int index = uid - PackageManagerService.FIRST_APPLICATION_UID;
            if (index < N) mUserIds.set(index, null);
        } else {
            mOtherUserIds.remove(uid);
        }
    }

    private void replaceUserIdLP(int uid, Object obj) {
        if (uid >= PackageManagerService.FIRST_APPLICATION_UID) {
            final int N = mUserIds.size();
            final int index = uid - PackageManagerService.FIRST_APPLICATION_UID;
            if (index < N) mUserIds.set(index, obj);
        } else {
            mOtherUserIds.put(uid, obj);
        }
    }

    void writeStoppedLP() {
        // Keep the old stopped packages around until we know the new ones have
        // been successfully written.
        if (mStoppedPackagesFilename.exists()) {
            // Presence of backup settings file indicates that we failed
            // to persist packages earlier. So preserve the older
            // backup for future reference since the current packages
            // might have been corrupted.
            if (!mBackupStoppedPackagesFilename.exists()) {
                if (!mStoppedPackagesFilename.renameTo(mBackupStoppedPackagesFilename)) {
                    Log.wtf(PackageManagerService.TAG, "Unable to backup package manager stopped packages, "
                            + "current changes will be lost at reboot");
                    return;
                }
            } else {
                mStoppedPackagesFilename.delete();
                Slog.w(PackageManagerService.TAG, "Preserving older stopped packages backup");
            }
        }

        try {
            FileOutputStream fstr = new FileOutputStream(mStoppedPackagesFilename);
            BufferedOutputStream str = new BufferedOutputStream(fstr);

            //XmlSerializer serializer = XmlUtils.serializerInstance();
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(str, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, "stopped-packages");

            for (PackageSetting pkg : mPackages.values()) {
                if (pkg.stopped) {
                    serializer.startTag(null, "pkg");
                    serializer.attribute(null, "name", pkg.name);
                    if (pkg.notLaunched) {
                        serializer.attribute(null, "nl", "1");
                    }
                    serializer.endTag(null, "pkg");
                }
            }

            serializer.endTag(null, "stopped-packages");

            serializer.endDocument();

            str.flush();
            FileUtils.sync(fstr);
            str.close();

            // New settings successfully written, old ones are no longer
            // needed.
            mBackupStoppedPackagesFilename.delete();
            FileUtils.setPermissions(mStoppedPackagesFilename.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP
                    |FileUtils.S_IROTH,
                    -1, -1);

            // Done, all is good!
            return;

        } catch(java.io.IOException e) {
            Log.wtf(PackageManagerService.TAG, "Unable to write package manager stopped packages, "
                    + " current changes will be lost at reboot", e);
        }

        // Clean up partially written files
        if (mStoppedPackagesFilename.exists()) {
            if (!mStoppedPackagesFilename.delete()) {
                Log.i(PackageManagerService.TAG, "Failed to clean up mangled file: " + mStoppedPackagesFilename);
            }
        }
    }

    // Note: assumed "stopped" field is already cleared in all packages.
    void readStoppedLP() {
        FileInputStream str = null;
        if (mBackupStoppedPackagesFilename.exists()) {
            try {
                str = new FileInputStream(mBackupStoppedPackagesFilename);
                mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO, "Need to read from backup stopped packages file");
                if (mSettingsFilename.exists()) {
                    // If both the backup and normal file exist, we
                    // ignore the normal one since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up stopped packages file "
                            + mStoppedPackagesFilename);
                    mStoppedPackagesFilename.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        try {
            if (str == null) {
                if (!mStoppedPackagesFilename.exists()) {
                    mReadMessages.append("No stopped packages file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO, "No stopped packages file file; "
                            + "assuming all started");
                    // At first boot, make sure no packages are stopped.
                    // We usually want to have third party apps initialize
                    // in the stopped state, but not at first boot.
                    for (PackageSetting pkg : mPackages.values()) {
                        pkg.stopped = false;
                        pkg.notLaunched = false;
                    }
                    return;
                }
                str = new FileInputStream(mStoppedPackagesFilename);
            }
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(str, null);

            int type;
            while ((type=parser.next()) != XmlPullParser.START_TAG
                       && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                mReadMessages.append("No start tag found in stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "No start tag found in package manager stopped packages");
                return;
            }

            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG
                           || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG
                        || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("pkg")) {
                    String name = parser.getAttributeValue(null, "name");
                    PackageSetting ps = mPackages.get(name);
                    if (ps != null) {
                        ps.stopped = true;
                        if ("1".equals(parser.getAttributeValue(null, "nl"))) {
                            ps.notLaunched = true;
                        }
                    } else {
                        Slog.w(PackageManagerService.TAG, "No package known for stopped package: " + name);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else {
                    Slog.w(PackageManagerService.TAG, "Unknown element under <stopped-packages>: "
                          + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

            str.close();

        } catch(XmlPullParserException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading stopped packages: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages", e);

        } catch(java.io.IOException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages", e);

        }
    }

    void writeLP() {
        //Debug.startMethodTracing("/data/system/packageprof", 8 * 1024 * 1024);

        // Keep the old settings around until we know the new ones have
        // been successfully written.
        if (mSettingsFilename.exists()) {
            // Presence of backup settings file indicates that we failed
            // to persist settings earlier. So preserve the older
            // backup for future reference since the current settings
            // might have been corrupted.
            if (!mBackupSettingsFilename.exists()) {
                if (!mSettingsFilename.renameTo(mBackupSettingsFilename)) {
                    Log.wtf(PackageManagerService.TAG, "Unable to backup package manager settings, "
                            + " current changes will be lost at reboot");
                    return;
                }
            } else {
                mSettingsFilename.delete();
                Slog.w(PackageManagerService.TAG, "Preserving older settings backup");
            }
        }

        mPastSignatures.clear();

        try {
            FileOutputStream fstr = new FileOutputStream(mSettingsFilename);
            BufferedOutputStream str = new BufferedOutputStream(fstr);

            //XmlSerializer serializer = XmlUtils.serializerInstance();
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(str, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, "packages");

            serializer.startTag(null, "last-platform-version");
            serializer.attribute(null, "internal", Integer.toString(mInternalSdkPlatform));
            serializer.attribute(null, "external", Integer.toString(mExternalSdkPlatform));
            serializer.endTag(null, "last-platform-version");
            
            serializer.startTag(null, "permission-trees");
            for (BasePermission bp : mPermissionTrees.values()) {
                writePermission(serializer, bp);
            }
            serializer.endTag(null, "permission-trees");

            serializer.startTag(null, "permissions");
            for (BasePermission bp : mPermissions.values()) {
                writePermission(serializer, bp);
            }
            serializer.endTag(null, "permissions");

            for (PackageSetting pkg : mPackages.values()) {
                writePackage(serializer, pkg);
            }

            for (PackageSetting pkg : mDisabledSysPackages.values()) {
                writeDisabledSysPackage(serializer, pkg);
            }

            serializer.startTag(null, "preferred-activities");
            for (PreferredActivity pa : mPreferredActivities.filterSet()) {
                serializer.startTag(null, "item");
                pa.writeToXml(serializer);
                serializer.endTag(null, "item");
            }
            serializer.endTag(null, "preferred-activities");

            for (SharedUserSetting usr : mSharedUsers.values()) {
                serializer.startTag(null, "shared-user");
                serializer.attribute(null, "name", usr.name);
                serializer.attribute(null, "userId",
                        Integer.toString(usr.userId));
                usr.signatures.writeXml(serializer, "sigs", mPastSignatures);
                serializer.startTag(null, "perms");
                for (String name : usr.grantedPermissions) {
                    serializer.startTag(null, "item");
                    serializer.attribute(null, "name", name);
                    serializer.endTag(null, "item");
                }
                serializer.endTag(null, "perms");
                serializer.endTag(null, "shared-user");
            }

            if (mPackagesToBeCleaned.size() > 0) {
                for (int i=0; i<mPackagesToBeCleaned.size(); i++) {
                    serializer.startTag(null, "cleaning-package");
                    serializer.attribute(null, "name", mPackagesToBeCleaned.get(i));
                    serializer.endTag(null, "cleaning-package");
                }
            }
            
            if (mRenamedPackages.size() > 0) {
                for (HashMap.Entry<String, String> e : mRenamedPackages.entrySet()) {
                    serializer.startTag(null, "renamed-package");
                    serializer.attribute(null, "new", e.getKey());
                    serializer.attribute(null, "old", e.getValue());
                    serializer.endTag(null, "renamed-package");
                }
            }
            
            serializer.endTag(null, "packages");

            serializer.endDocument();

            str.flush();
            FileUtils.sync(fstr);
            str.close();

            // New settings successfully written, old ones are no longer
            // needed.
            mBackupSettingsFilename.delete();
            FileUtils.setPermissions(mSettingsFilename.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP
                    |FileUtils.S_IROTH,
                    -1, -1);

            // Write package list file now, use a JournaledFile.
            //
            File tempFile = new File(mPackageListFilename.toString() + ".tmp");
            JournaledFile journal = new JournaledFile(mPackageListFilename, tempFile);

            fstr = new FileOutputStream(journal.chooseForWrite());
            str = new BufferedOutputStream(fstr);
            try {
                StringBuilder sb = new StringBuilder();
                for (PackageSetting pkg : mPackages.values()) {
                    ApplicationInfo ai = pkg.pkg.applicationInfo;
                    String dataPath = ai.dataDir;
                    boolean isDebug  = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

                    // Avoid any application that has a space in its path
                    // or that is handled by the system.
                    if (dataPath.indexOf(" ") >= 0 || ai.uid <= Process.FIRST_APPLICATION_UID)
                        continue;

                    // we store on each line the following information for now:
                    //
                    // pkgName    - package name
                    // userId     - application-specific user id
                    // debugFlag  - 0 or 1 if the package is debuggable.
                    // dataPath   - path to package's data path
                    //
                    // NOTE: We prefer not to expose all ApplicationInfo flags for now.
                    //
                    // DO NOT MODIFY THIS FORMAT UNLESS YOU CAN ALSO MODIFY ITS USERS
                    // FROM NATIVE CODE. AT THE MOMENT, LOOK AT THE FOLLOWING SOURCES:
                    //   system/core/run-as/run-as.c
                    //
                    sb.setLength(0);
                    sb.append(ai.packageName);
                    sb.append(" ");
                    sb.append((int)ai.uid);
                    sb.append(isDebug ? " 1 " : " 0 ");
                    sb.append(dataPath);
                    sb.append("\n");
                    str.write(sb.toString().getBytes());
                }
                str.flush();
                FileUtils.sync(fstr);
                str.close();
                journal.commit();
            }
            catch (Exception  e) {
                journal.rollback();
            }

            FileUtils.setPermissions(mPackageListFilename.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP
                    |FileUtils.S_IROTH,
                    -1, -1);

            writeStoppedLP();

            return;

        } catch(XmlPullParserException e) {
            Log.wtf(PackageManagerService.TAG, "Unable to write package manager settings, "
                    + "current changes will be lost at reboot", e);
        } catch(java.io.IOException e) {
            Log.wtf(PackageManagerService.TAG, "Unable to write package manager settings, "
                    + "current changes will be lost at reboot", e);
        }
        // Clean up partially written files
        if (mSettingsFilename.exists()) {
            if (!mSettingsFilename.delete()) {
                Log.wtf(PackageManagerService.TAG, "Failed to clean up mangled file: " + mSettingsFilename);
            }
        }
        //Debug.stopMethodTracing();
    }

    void writeDisabledSysPackage(XmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "updated-package");
        serializer.attribute(null, "name", pkg.name);
        if (pkg.realName != null) {
            serializer.attribute(null, "realName", pkg.realName);
        }
        serializer.attribute(null, "codePath", pkg.codePathString);
        serializer.attribute(null, "ft", Long.toHexString(pkg.timeStamp));
        serializer.attribute(null, "it", Long.toHexString(pkg.firstInstallTime));
        serializer.attribute(null, "ut", Long.toHexString(pkg.lastUpdateTime));
        serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
        if (!pkg.resourcePathString.equals(pkg.codePathString)) {
            serializer.attribute(null, "resourcePath", pkg.resourcePathString);
        }
        if (pkg.nativeLibraryPathString != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.nativeLibraryPathString);
        }
        if (pkg.sharedUser == null) {
            serializer.attribute(null, "userId", Integer.toString(pkg.userId));
        } else {
            serializer.attribute(null, "sharedUserId", Integer.toString(pkg.userId));
        }
        serializer.startTag(null, "perms");
        if (pkg.sharedUser == null) {
            // If this is a shared user, the permissions will
            // be written there. We still need to write an
            // empty permissions list so permissionsFixed will
            // be set.
            for (final String name : pkg.grantedPermissions) {
                BasePermission bp = mPermissions.get(name);
                if (bp != null) {
                    // We only need to write signature or system permissions but
                    // this wont
                    // match the semantics of grantedPermissions. So write all
                    // permissions.
                    serializer.startTag(null, "item");
                    serializer.attribute(null, "name", name);
                    serializer.endTag(null, "item");
                }
            }
        }
        serializer.endTag(null, "perms");
        serializer.endTag(null, "updated-package");
    }

    void writePackage(XmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "package");
        serializer.attribute(null, "name", pkg.name);
        if (pkg.realName != null) {
            serializer.attribute(null, "realName", pkg.realName);
        }
        serializer.attribute(null, "codePath", pkg.codePathString);
        if (!pkg.resourcePathString.equals(pkg.codePathString)) {
            serializer.attribute(null, "resourcePath", pkg.resourcePathString);
        }
        if (pkg.nativeLibraryPathString != null) {
            serializer.attribute(null, "nativeLibraryPath", pkg.nativeLibraryPathString);
        }
        serializer.attribute(null, "flags", Integer.toString(pkg.pkgFlags));
        serializer.attribute(null, "ft", Long.toHexString(pkg.timeStamp));
        serializer.attribute(null, "it", Long.toHexString(pkg.firstInstallTime));
        serializer.attribute(null, "ut", Long.toHexString(pkg.lastUpdateTime));
        serializer.attribute(null, "version", String.valueOf(pkg.versionCode));
        if (pkg.sharedUser == null) {
            serializer.attribute(null, "userId", Integer.toString(pkg.userId));
        } else {
            serializer.attribute(null, "sharedUserId", Integer.toString(pkg.userId));
        }
        if (pkg.uidError) {
            serializer.attribute(null, "uidError", "true");
        }
        if (pkg.enabled != COMPONENT_ENABLED_STATE_DEFAULT) {
            serializer.attribute(null, "enabled",
                    pkg.enabled == COMPONENT_ENABLED_STATE_ENABLED ? "true" : "false");
        }
        if (pkg.installStatus == PackageSettingBase.PKG_INSTALL_INCOMPLETE) {
            serializer.attribute(null, "installStatus", "false");
        }
        if (pkg.installerPackageName != null) {
            serializer.attribute(null, "installer", pkg.installerPackageName);
        }
        pkg.signatures.writeXml(serializer, "sigs", mPastSignatures);
        if ((pkg.pkgFlags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            serializer.startTag(null, "perms");
            if (pkg.sharedUser == null) {
                // If this is a shared user, the permissions will
                // be written there. We still need to write an
                // empty permissions list so permissionsFixed will
                // be set.
                for (final String name : pkg.grantedPermissions) {
                    serializer.startTag(null, "item");
                    serializer.attribute(null, "name", name);
                    serializer.endTag(null, "item");
                }
            }
            serializer.endTag(null, "perms");
        }
        if (pkg.disabledComponents.size() > 0) {
            serializer.startTag(null, "disabled-components");
            for (final String name : pkg.disabledComponents) {
                serializer.startTag(null, "item");
                serializer.attribute(null, "name", name);
                serializer.endTag(null, "item");
            }
            serializer.endTag(null, "disabled-components");
        }
        if (pkg.enabledComponents.size() > 0) {
            serializer.startTag(null, "enabled-components");
            for (final String name : pkg.enabledComponents) {
                serializer.startTag(null, "item");
                serializer.attribute(null, "name", name);
                serializer.endTag(null, "item");
            }
            serializer.endTag(null, "enabled-components");
        }

        serializer.endTag(null, "package");
    }

    void writePermission(XmlSerializer serializer, BasePermission bp)
            throws XmlPullParserException, java.io.IOException {
        if (bp.type != BasePermission.TYPE_BUILTIN && bp.sourcePackage != null) {
            serializer.startTag(null, "item");
            serializer.attribute(null, "name", bp.name);
            serializer.attribute(null, "package", bp.sourcePackage);
            if (bp.protectionLevel != PermissionInfo.PROTECTION_NORMAL) {
                serializer.attribute(null, "protection", Integer.toString(bp.protectionLevel));
            }
            if (PackageManagerService.DEBUG_SETTINGS)
                Log.v(PackageManagerService.TAG, "Writing perm: name=" + bp.name + " type="
                        + bp.type);
            if (bp.type == BasePermission.TYPE_DYNAMIC) {
                PermissionInfo pi = bp.perm != null ? bp.perm.info : bp.pendingInfo;
                if (pi != null) {
                    serializer.attribute(null, "type", "dynamic");
                    if (pi.icon != 0) {
                        serializer.attribute(null, "icon", Integer.toString(pi.icon));
                    }
                    if (pi.nonLocalizedLabel != null) {
                        serializer.attribute(null, "label", pi.nonLocalizedLabel.toString());
                    }
                }
            }
            serializer.endTag(null, "item");
        }
    }

    ArrayList<PackageSetting> getListOfIncompleteInstallPackages() {
        HashSet<String> kList = new HashSet<String>(mPackages.keySet());
        Iterator<String> its = kList.iterator();
        ArrayList<PackageSetting> ret = new ArrayList<PackageSetting>();
        while (its.hasNext()) {
            String key = its.next();
            PackageSetting ps = mPackages.get(key);
            if (ps.getInstallStatus() == PackageSettingBase.PKG_INSTALL_INCOMPLETE) {
                ret.add(ps);
            }
        }
        return ret;
    }

    boolean readLP() {
        FileInputStream str = null;
        if (mBackupSettingsFilename.exists()) {
            try {
                str = new FileInputStream(mBackupSettingsFilename);
                mReadMessages.append("Reading from backup settings file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup settings file");
                if (mSettingsFilename.exists()) {
                    // If both the backup and settings file exist, we
                    // ignore the settings since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up settings file "
                            + mSettingsFilename);
                    mSettingsFilename.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        mPastSignatures.clear();

        try {
            if (str == null) {
                if (!mSettingsFilename.exists()) {
                    mReadMessages.append("No settings file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No settings file; creating initial state");
                    return false;
                }
                str = new FileInputStream(mSettingsFilename);
            }
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(str, null);

            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                mReadMessages.append("No start tag found in settings file\n");
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "No start tag found in package manager settings");
                Log
                        .wtf(PackageManagerService.TAG,
                                "No start tag found in package manager settings");
                return false;
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("package")) {
                    readPackageLP(parser);
                } else if (tagName.equals("permissions")) {
                    readPermissionsLP(mPermissions, parser);
                } else if (tagName.equals("permission-trees")) {
                    readPermissionsLP(mPermissionTrees, parser);
                } else if (tagName.equals("shared-user")) {
                    readSharedUserLP(parser);
                } else if (tagName.equals("preferred-packages")) {
                    // no longer used.
                } else if (tagName.equals("preferred-activities")) {
                    readPreferredActivitiesLP(parser);
                } else if (tagName.equals("updated-package")) {
                    readDisabledSysPackageLP(parser);
                } else if (tagName.equals("cleaning-package")) {
                    String name = parser.getAttributeValue(null, "name");
                    if (name != null) {
                        mPackagesToBeCleaned.add(name);
                    }
                } else if (tagName.equals("renamed-package")) {
                    String nname = parser.getAttributeValue(null, "new");
                    String oname = parser.getAttributeValue(null, "old");
                    if (nname != null && oname != null) {
                        mRenamedPackages.put(nname, oname);
                    }
                } else if (tagName.equals("last-platform-version")) {
                    mInternalSdkPlatform = mExternalSdkPlatform = 0;
                    try {
                        String internal = parser.getAttributeValue(null, "internal");
                        if (internal != null) {
                            mInternalSdkPlatform = Integer.parseInt(internal);
                        }
                        String external = parser.getAttributeValue(null, "external");
                        if (external != null) {
                            mExternalSdkPlatform = Integer.parseInt(external);
                        }
                    } catch (NumberFormatException e) {
                    }
                } else {
                    Slog.w(PackageManagerService.TAG, "Unknown element under <packages>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

            str.close();

        } catch (XmlPullParserException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager settings", e);

        } catch (java.io.IOException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager settings", e);

        }

        final int N = mPendingPackages.size();
        for (int i = 0; i < N; i++) {
            final PendingPackage pp = mPendingPackages.get(i);
            Object idObj = getUserIdLP(pp.sharedId);
            if (idObj != null && idObj instanceof SharedUserSetting) {
                PackageSetting p = getPackageLP(pp.name, null, pp.realName,
                        (SharedUserSetting) idObj, pp.codePath, pp.resourcePath,
                        pp.nativeLibraryPathString, pp.versionCode, pp.pkgFlags, true, true);
                if (p == null) {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unable to create application package for " + pp.name);
                    continue;
                }
                p.copyFrom(pp);
            } else if (idObj != null) {
                String msg = "Bad package setting: package " + pp.name + " has shared uid "
                        + pp.sharedId + " that is not a shared uid\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            } else {
                String msg = "Bad package setting: package " + pp.name + " has shared uid "
                        + pp.sharedId + " that is not defined\n";
                mReadMessages.append(msg);
                PackageManagerService.reportSettingsProblem(Log.ERROR, msg);
            }
        }
        mPendingPackages.clear();

        readStoppedLP();

        mReadMessages.append("Read completed successfully: " + mPackages.size() + " packages, "
                + mSharedUsers.size() + " shared uids\n");

        return true;
    }

    private int readInt(XmlPullParser parser, String ns, String name, int defValue) {
        String v = parser.getAttributeValue(ns, name);
        try {
            if (v == null) {
                return defValue;
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: attribute " + name
                            + " has bad integer value " + v + " at "
                            + parser.getPositionDescription());
        }
        return defValue;
    }

    private void readPermissionsLP(HashMap<String, BasePermission> out, XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("item")) {
                String name = parser.getAttributeValue(null, "name");
                String sourcePackage = parser.getAttributeValue(null, "package");
                String ptype = parser.getAttributeValue(null, "type");
                if (name != null && sourcePackage != null) {
                    boolean dynamic = "dynamic".equals(ptype);
                    BasePermission bp = new BasePermission(name, sourcePackage,
                            dynamic ? BasePermission.TYPE_DYNAMIC : BasePermission.TYPE_NORMAL);
                    bp.protectionLevel = readInt(parser, null, "protection",
                            PermissionInfo.PROTECTION_NORMAL);
                    if (dynamic) {
                        PermissionInfo pi = new PermissionInfo();
                        pi.packageName = sourcePackage.intern();
                        pi.name = name.intern();
                        pi.icon = readInt(parser, null, "icon", 0);
                        pi.nonLocalizedLabel = parser.getAttributeValue(null, "label");
                        pi.protectionLevel = bp.protectionLevel;
                        bp.pendingInfo = pi;
                    }
                    out.put(bp.name, bp);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: permissions has" + " no name at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element reading permissions: " + parser.getName() + " at "
                                + parser.getPositionDescription());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readDisabledSysPackageLP(XmlPullParser parser) throws XmlPullParserException,
            IOException {
        String name = parser.getAttributeValue(null, "name");
        String realName = parser.getAttributeValue(null, "realName");
        String codePathStr = parser.getAttributeValue(null, "codePath");
        String resourcePathStr = parser.getAttributeValue(null, "resourcePath");
        String nativeLibraryPathStr = parser.getAttributeValue(null, "nativeLibraryPath");
        if (resourcePathStr == null) {
            resourcePathStr = codePathStr;
        }
        String version = parser.getAttributeValue(null, "version");
        int versionCode = 0;
        if (version != null) {
            try {
                versionCode = Integer.parseInt(version);
            } catch (NumberFormatException e) {
            }
        }

        int pkgFlags = 0;
        pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
        PackageSetting ps = new PackageSetting(name, realName, new File(codePathStr),
                new File(resourcePathStr), nativeLibraryPathStr, versionCode, pkgFlags);
        String timeStampStr = parser.getAttributeValue(null, "ft");
        if (timeStampStr != null) {
            try {
                long timeStamp = Long.parseLong(timeStampStr, 16);
                ps.setTimeStamp(timeStamp);
            } catch (NumberFormatException e) {
            }
        } else {
            timeStampStr = parser.getAttributeValue(null, "ts");
            if (timeStampStr != null) {
                try {
                    long timeStamp = Long.parseLong(timeStampStr);
                    ps.setTimeStamp(timeStamp);
                } catch (NumberFormatException e) {
                }
            }
        }
        timeStampStr = parser.getAttributeValue(null, "it");
        if (timeStampStr != null) {
            try {
                ps.firstInstallTime = Long.parseLong(timeStampStr, 16);
            } catch (NumberFormatException e) {
            }
        }
        timeStampStr = parser.getAttributeValue(null, "ut");
        if (timeStampStr != null) {
            try {
                ps.lastUpdateTime = Long.parseLong(timeStampStr, 16);
            } catch (NumberFormatException e) {
            }
        }
        String idStr = parser.getAttributeValue(null, "userId");
        ps.userId = idStr != null ? Integer.parseInt(idStr) : 0;
        if (ps.userId <= 0) {
            String sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
            ps.userId = sharedIdStr != null ? Integer.parseInt(sharedIdStr) : 0;
        }
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("perms")) {
                readGrantedPermissionsLP(parser, ps.grantedPermissions);
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <updated-package>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        mDisabledSysPackages.put(name, ps);
    }

    private void readPackageLP(XmlPullParser parser) throws XmlPullParserException, IOException {
        String name = null;
        String realName = null;
        String idStr = null;
        String sharedIdStr = null;
        String codePathStr = null;
        String resourcePathStr = null;
        String nativeLibraryPathStr = null;
        String systemStr = null;
        String installerPackageName = null;
        String uidError = null;
        int pkgFlags = 0;
        long timeStamp = 0;
        long firstInstallTime = 0;
        long lastUpdateTime = 0;
        PackageSettingBase packageSetting = null;
        String version = null;
        int versionCode = 0;
        try {
            name = parser.getAttributeValue(null, "name");
            realName = parser.getAttributeValue(null, "realName");
            idStr = parser.getAttributeValue(null, "userId");
            uidError = parser.getAttributeValue(null, "uidError");
            sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
            codePathStr = parser.getAttributeValue(null, "codePath");
            resourcePathStr = parser.getAttributeValue(null, "resourcePath");
            nativeLibraryPathStr = parser.getAttributeValue(null, "nativeLibraryPath");
            version = parser.getAttributeValue(null, "version");
            if (version != null) {
                try {
                    versionCode = Integer.parseInt(version);
                } catch (NumberFormatException e) {
                }
            }
            installerPackageName = parser.getAttributeValue(null, "installer");

            systemStr = parser.getAttributeValue(null, "flags");
            if (systemStr != null) {
                try {
                    pkgFlags = Integer.parseInt(systemStr);
                } catch (NumberFormatException e) {
                }
            } else {
                // For backward compatibility
                systemStr = parser.getAttributeValue(null, "system");
                if (systemStr != null) {
                    pkgFlags |= ("true".equalsIgnoreCase(systemStr)) ? ApplicationInfo.FLAG_SYSTEM
                            : 0;
                } else {
                    // Old settings that don't specify system... just treat
                    // them as system, good enough.
                    pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
                }
            }
            String timeStampStr = parser.getAttributeValue(null, "ft");
            if (timeStampStr != null) {
                try {
                    timeStamp = Long.parseLong(timeStampStr, 16);
                } catch (NumberFormatException e) {
                }
            } else {
                timeStampStr = parser.getAttributeValue(null, "ts");
                if (timeStampStr != null) {
                    try {
                        timeStamp = Long.parseLong(timeStampStr);
                    } catch (NumberFormatException e) {
                    }
                }
            }
            timeStampStr = parser.getAttributeValue(null, "it");
            if (timeStampStr != null) {
                try {
                    firstInstallTime = Long.parseLong(timeStampStr, 16);
                } catch (NumberFormatException e) {
                }
            }
            timeStampStr = parser.getAttributeValue(null, "ut");
            if (timeStampStr != null) {
                try {
                    lastUpdateTime = Long.parseLong(timeStampStr, 16);
                } catch (NumberFormatException e) {
                }
            }
            if (PackageManagerService.DEBUG_SETTINGS)
                Log.v(PackageManagerService.TAG, "Reading package: " + name + " userId=" + idStr
                        + " sharedUserId=" + sharedIdStr);
            int userId = idStr != null ? Integer.parseInt(idStr) : 0;
            if (resourcePathStr == null) {
                resourcePathStr = codePathStr;
            }
            if (realName != null) {
                realName = realName.intern();
            }
            if (name == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <package> has no name at "
                                + parser.getPositionDescription());
            } else if (codePathStr == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <package> has no codePath at "
                                + parser.getPositionDescription());
            } else if (userId > 0) {
                packageSetting = addPackageLP(name.intern(), realName, new File(codePathStr),
                        new File(resourcePathStr), nativeLibraryPathStr, userId, versionCode,
                        pkgFlags);
                if (PackageManagerService.DEBUG_SETTINGS)
                    Log.i(PackageManagerService.TAG, "Reading package " + name + ": userId="
                            + userId + " pkg=" + packageSetting);
                if (packageSetting == null) {
                    PackageManagerService.reportSettingsProblem(Log.ERROR, "Failure adding uid "
                            + userId + " while parsing settings at "
                            + parser.getPositionDescription());
                } else {
                    packageSetting.setTimeStamp(timeStamp);
                    packageSetting.firstInstallTime = firstInstallTime;
                    packageSetting.lastUpdateTime = lastUpdateTime;
                }
            } else if (sharedIdStr != null) {
                userId = sharedIdStr != null ? Integer.parseInt(sharedIdStr) : 0;
                if (userId > 0) {
                    packageSetting = new PendingPackage(name.intern(), realName, new File(
                            codePathStr), new File(resourcePathStr), nativeLibraryPathStr, userId,
                            versionCode, pkgFlags);
                    packageSetting.setTimeStamp(timeStamp);
                    packageSetting.firstInstallTime = firstInstallTime;
                    packageSetting.lastUpdateTime = lastUpdateTime;
                    mPendingPackages.add((PendingPackage) packageSetting);
                    if (PackageManagerService.DEBUG_SETTINGS)
                        Log.i(PackageManagerService.TAG, "Reading package " + name
                                + ": sharedUserId=" + userId + " pkg=" + packageSetting);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: package " + name
                                    + " has bad sharedId " + sharedIdStr + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: package " + name + " has bad userId "
                                + idStr + " at " + parser.getPositionDescription());
            }
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: package " + name + " has bad userId "
                            + idStr + " at " + parser.getPositionDescription());
        }
        if (packageSetting != null) {
            packageSetting.uidError = "true".equals(uidError);
            packageSetting.installerPackageName = installerPackageName;
            packageSetting.nativeLibraryPathString = nativeLibraryPathStr;
            final String enabledStr = parser.getAttributeValue(null, "enabled");
            if (enabledStr != null) {
                if (enabledStr.equalsIgnoreCase("true")) {
                    packageSetting.enabled = COMPONENT_ENABLED_STATE_ENABLED;
                } else if (enabledStr.equalsIgnoreCase("false")) {
                    packageSetting.enabled = COMPONENT_ENABLED_STATE_DISABLED;
                } else if (enabledStr.equalsIgnoreCase("default")) {
                    packageSetting.enabled = COMPONENT_ENABLED_STATE_DEFAULT;
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: package " + name
                                    + " has bad enabled value: " + idStr + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                packageSetting.enabled = COMPONENT_ENABLED_STATE_DEFAULT;
            }
            final String installStatusStr = parser.getAttributeValue(null, "installStatus");
            if (installStatusStr != null) {
                if (installStatusStr.equalsIgnoreCase("false")) {
                    packageSetting.installStatus = PackageSettingBase.PKG_INSTALL_INCOMPLETE;
                } else {
                    packageSetting.installStatus = PackageSettingBase.PKG_INSTALL_COMPLETE;
                }
            }

            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("disabled-components")) {
                    readDisabledComponentsLP(packageSetting, parser);
                } else if (tagName.equals("enabled-components")) {
                    readEnabledComponentsLP(packageSetting, parser);
                } else if (tagName.equals("sigs")) {
                    packageSetting.signatures.readXml(parser, mPastSignatures);
                } else if (tagName.equals("perms")) {
                    readGrantedPermissionsLP(parser, packageSetting.grantedPermissions);
                    packageSetting.permissionsFixed = true;
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element under <package>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readDisabledComponentsLP(PackageSettingBase packageSetting, XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("item")) {
                String name = parser.getAttributeValue(null, "name");
                if (name != null) {
                    packageSetting.disabledComponents.add(name.intern());
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <disabled-components> has"
                                    + " no name at " + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <disabled-components>: " + parser.getName());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readEnabledComponentsLP(PackageSettingBase packageSetting, XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("item")) {
                String name = parser.getAttributeValue(null, "name");
                if (name != null) {
                    packageSetting.enabledComponents.add(name.intern());
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <enabled-components> has"
                                    + " no name at " + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <enabled-components>: " + parser.getName());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readSharedUserLP(XmlPullParser parser) throws XmlPullParserException, IOException {
        String name = null;
        String idStr = null;
        int pkgFlags = 0;
        SharedUserSetting su = null;
        try {
            name = parser.getAttributeValue(null, "name");
            idStr = parser.getAttributeValue(null, "userId");
            int userId = idStr != null ? Integer.parseInt(idStr) : 0;
            if ("true".equals(parser.getAttributeValue(null, "system"))) {
                pkgFlags |= ApplicationInfo.FLAG_SYSTEM;
            }
            if (name == null) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: <shared-user> has no name at "
                                + parser.getPositionDescription());
            } else if (userId == 0) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Error in package manager settings: shared-user " + name
                                + " has bad userId " + idStr + " at "
                                + parser.getPositionDescription());
            } else {
                if ((su = addSharedUserLP(name.intern(), userId, pkgFlags)) == null) {
                    PackageManagerService
                            .reportSettingsProblem(Log.ERROR, "Occurred while parsing settings at "
                                    + parser.getPositionDescription());
                }
            }
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: package " + name + " has bad userId "
                            + idStr + " at " + parser.getPositionDescription());
        }
        ;

        if (su != null) {
            int outerDepth = parser.getDepth();
            int type;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("sigs")) {
                    su.signatures.readXml(parser, mPastSignatures);
                } else if (tagName.equals("perms")) {
                    readGrantedPermissionsLP(parser, su.grantedPermissions);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Unknown element under <shared-user>: " + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

        } else {
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readGrantedPermissionsLP(XmlPullParser parser, HashSet<String> outPerms)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("item")) {
                String name = parser.getAttributeValue(null, "name");
                if (name != null) {
                    outPerms.add(name.intern());
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <perms> has" + " no name at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <perms>: " + parser.getName());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private void readPreferredActivitiesLP(XmlPullParser parser) throws XmlPullParserException,
            IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("item")) {
                PreferredActivity pa = new PreferredActivity(parser);
                if (pa.mPref.getParseError() == null) {
                    mPreferredActivities.addFilter(pa);
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: <preferred-activity> "
                                    + pa.mPref.getParseError() + " at "
                                    + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <preferred-activities>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    // Returns -1 if we could not find an available UserId to assign
    private int newUserIdLP(Object obj) {
        // Let's be stupidly inefficient for now...
        final int N = mUserIds.size();
        for (int i = 0; i < N; i++) {
            if (mUserIds.get(i) == null) {
                mUserIds.set(i, obj);
                return PackageManagerService.FIRST_APPLICATION_UID + i;
            }
        }

        // None left?
        if (N >= PackageManagerService.MAX_APPLICATION_UIDS) {
            return -1;
        }

        mUserIds.add(obj);
        return PackageManagerService.FIRST_APPLICATION_UID + N;
    }

    public PackageSetting getDisabledSystemPkg(String name) {
        synchronized (mPackages) {
            PackageSetting ps = mDisabledSysPackages.get(name);
            return ps;
        }
    }

    boolean isEnabledLP(ComponentInfo componentInfo, int flags) {
        if ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
            return true;
        }
        final PackageSetting packageSettings = mPackages.get(componentInfo.packageName);
        if (PackageManagerService.DEBUG_SETTINGS) {
            Log.v(PackageManagerService.TAG, "isEnabledLock - packageName = " + componentInfo.packageName
                       + " componentName = " + componentInfo.name);
            Log.v(PackageManagerService.TAG, "enabledComponents: "
                       + Arrays.toString(packageSettings.enabledComponents.toArray()));
            Log.v(PackageManagerService.TAG, "disabledComponents: "
                       + Arrays.toString(packageSettings.disabledComponents.toArray()));
        }
        if (packageSettings == null) {
            return false;
        }
        if (packageSettings.enabled == COMPONENT_ENABLED_STATE_DISABLED
                || (packageSettings.pkg != null && !packageSettings.pkg.applicationInfo.enabled
                        && packageSettings.enabled == COMPONENT_ENABLED_STATE_DEFAULT)) {
            return false;
        }
        if (packageSettings.enabledComponents.contains(componentInfo.name)) {
            return true;
        }
        if (packageSettings.disabledComponents.contains(componentInfo.name)) {
            return false;
        }
        return componentInfo.enabled;
    }
}