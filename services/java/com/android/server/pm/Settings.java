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
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.Manifest.permission.READ_EXTERNAL_STORAGE;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.JournaledFile;
import com.android.internal.util.XmlUtils;
import com.android.server.IntentResolver;
import com.android.server.pm.PackageManagerService.DumpState;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageCleanItem;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.content.pm.UserInfo;
import android.content.pm.PackageUserState;
import android.content.pm.VerifierDeviceIdentity;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Process;
import android.os.UserHandle;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import libcore.io.IoUtils;

/**
 * Holds information about dynamic settings.
 */
final class Settings {
    private static final String TAG = "PackageSettings";

    private static final boolean DEBUG_STOPPED = false;
    private static final boolean DEBUG_MU = false;

    private static final String TAG_READ_EXTERNAL_STORAGE = "read-external-storage";
    private static final String ATTR_ENFORCEMENT = "enforcement";

    private static final String TAG_ITEM = "item";
    private static final String TAG_DISABLED_COMPONENTS = "disabled-components";
    private static final String TAG_ENABLED_COMPONENTS = "enabled-components";
    private static final String TAG_PACKAGE_RESTRICTIONS = "package-restrictions";
    private static final String TAG_PACKAGE = "pkg";

    private static final String ATTR_NAME = "name";
    private static final String ATTR_USER = "user";
    private static final String ATTR_CODE = "code";
    private static final String ATTR_NOT_LAUNCHED = "nl";
    private static final String ATTR_ENABLED = "enabled";
    private static final String ATTR_STOPPED = "stopped";
    private static final String ATTR_INSTALLED = "inst";

    private final File mSettingsFilename;
    private final File mBackupSettingsFilename;
    private final File mPackageListFilename;
    private final File mStoppedPackagesFilename;
    private final File mBackupStoppedPackagesFilename;
    final HashMap<String, PackageSetting> mPackages =
            new HashMap<String, PackageSetting>();
    // List of replaced system applications
    private final HashMap<String, PackageSetting> mDisabledSysPackages =
        new HashMap<String, PackageSetting>();

    // These are the last platform API version we were using for
    // the apps installed on internal and external storage.  It is
    // used to grant newer permissions one time during a system upgrade.
    int mInternalSdkPlatform;
    int mExternalSdkPlatform;

    Boolean mReadExternalStorageEnforced;

    /** Device identity for the purpose of package verification. */
    private VerifierDeviceIdentity mVerifierDeviceIdentity;

    // The user's preferred activities associated with particular intent
    // filters.
    final SparseArray<PreferredIntentResolver> mPreferredActivities =
            new SparseArray<PreferredIntentResolver>();

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
    final ArrayList<PackageCleanItem> mPackagesToBeCleaned = new ArrayList<PackageCleanItem>();
    
    // Packages that have been renamed since they were first installed.
    // Keys are the new names of the packages, values are the original
    // names.  The packages appear everwhere else under their original
    // names.
    final HashMap<String, String> mRenamedPackages = new HashMap<String, String>();
    
    final StringBuilder mReadMessages = new StringBuilder();

    /**
     * Used to track packages that have a shared user ID that hasn't been read
     * in yet.
     * <p>
     * TODO: make this just a local variable that is passed in during package
     * scanning to make it less confusing.
     */
    private final ArrayList<PendingPackage> mPendingPackages = new ArrayList<PendingPackage>();

    private final Context mContext;

    private final File mSystemDir;
    Settings(Context context) {
        this(context, Environment.getDataDirectory());
    }

    Settings(Context context, File dataDir) {
        mContext = context;
        mSystemDir = new File(dataDir, "system");
        mSystemDir.mkdirs();
        FileUtils.setPermissions(mSystemDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        mSettingsFilename = new File(mSystemDir, "packages.xml");
        mBackupSettingsFilename = new File(mSystemDir, "packages-backup.xml");
        mPackageListFilename = new File(mSystemDir, "packages.list");
        // Deprecated: Needed for migration
        mStoppedPackagesFilename = new File(mSystemDir, "packages-stopped.xml");
        mBackupStoppedPackagesFilename = new File(mSystemDir, "packages-stopped-backup.xml");
    }

    PackageSetting getPackageLPw(PackageParser.Package pkg, PackageSetting origPackage,
            String realName, SharedUserSetting sharedUser, File codePath, File resourcePath,
            String nativeLibraryPathString, int pkgFlags, UserHandle user, boolean add) {
        final String name = pkg.packageName;
        PackageSetting p = getPackageLPw(name, origPackage, realName, sharedUser, codePath,
                resourcePath, nativeLibraryPathString, pkg.mVersionCode, pkgFlags,
                user, add, true /* allowInstall */);
        return p;
    }

    PackageSetting peekPackageLPr(String name) {
        return mPackages.get(name);
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

    SharedUserSetting getSharedUserLPw(String name,
            int pkgFlags, boolean create) {
        SharedUserSetting s = mSharedUsers.get(name);
        if (s == null) {
            if (!create) {
                return null;
            }
            s = new SharedUserSetting(name, pkgFlags);
            s.userId = newUserIdLPw(s);
            Log.i(PackageManagerService.TAG, "New shared user " + name + ": id=" + s.userId);
            // < 0 means we couldn't assign a userid; fall out and return
            // s, which is currently null
            if (s.userId >= 0) {
                mSharedUsers.put(name, s);
            }
        }

        return s;
    }

    boolean disableSystemPackageLPw(String name) {
        final PackageSetting p = mPackages.get(name);
        if(p == null) {
            Log.w(PackageManagerService.TAG, "Package:"+name+" is not an installed package");
            return false;
        }
        final PackageSetting dp = mDisabledSysPackages.get(name);
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
            replacePackageLPw(name, newp);
            return true;
        }
        return false;
    }

    PackageSetting enableSystemPackageLPw(String name) {
        PackageSetting p = mDisabledSysPackages.get(name);
        if(p == null) {
            Log.w(PackageManagerService.TAG, "Package:"+name+" is not disabled");
            return null;
        }
        // Reset flag in ApplicationInfo object
        if((p.pkg != null) && (p.pkg.applicationInfo != null)) {
            p.pkg.applicationInfo.flags &= ~ApplicationInfo.FLAG_UPDATED_SYSTEM_APP;
        }
        PackageSetting ret = addPackageLPw(name, p.realName, p.codePath, p.resourcePath,
                p.nativeLibraryPathString, p.appId, p.versionCode, p.pkgFlags);
        mDisabledSysPackages.remove(name);
        return ret;
    }

    boolean isDisabledSystemPackageLPr(String name) {
        return mDisabledSysPackages.containsKey(name);
    }

    void removeDisabledSystemPackageLPw(String name) {
        mDisabledSysPackages.remove(name);
    }

    PackageSetting addPackageLPw(String name, String realName, File codePath, File resourcePath,
            String nativeLibraryPathString, int uid, int vc, int pkgFlags) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (p.appId == uid) {
                return p;
            }
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Adding duplicate package, keeping first: " + name);
            return null;
        }
        p = new PackageSetting(name, realName, codePath, resourcePath, nativeLibraryPathString,
                vc, pkgFlags);
        p.appId = uid;
        if (addUserIdLPw(uid, p, name)) {
            mPackages.put(name, p);
            return p;
        }
        return null;
    }

    SharedUserSetting addSharedUserLPw(String name, int uid, int pkgFlags) {
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
        if (addUserIdLPw(uid, s, name)) {
            mSharedUsers.put(name, s);
            return s;
        }
        return null;
    }

    // Transfer ownership of permissions from one package to another.
    void transferPermissionsLPw(String origPkg, String newPkg) {
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

    private PackageSetting getPackageLPw(String name, PackageSetting origPackage,
            String realName, SharedUserSetting sharedUser, File codePath, File resourcePath,
            String nativeLibraryPathString, int vc, int pkgFlags,
            UserHandle installUser, boolean add, boolean allowInstall) {
        PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (!p.codePath.equals(codePath)) {
                // Check to see if its a disabled system app
                if ((p.pkgFlags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    // This is an updated system app with versions in both system
                    // and data partition. Just let the most recent version
                    // take precedence.
                    Slog.w(PackageManagerService.TAG, "Trying to update system app code path from "
                            + p.codePathString + " to " + codePath.toString());
                } else {
                    // Just a change in the code path is not an issue, but
                    // let's log a message about it.
                    Slog.i(PackageManagerService.TAG, "Package " + name + " codePath changed from "
                            + p.codePath + " to " + codePath + "; Retaining data and using new");
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
            if (origPackage != null) {
                // We are consuming the data from an existing package.
                p = new PackageSetting(origPackage.name, name, codePath, resourcePath,
                        nativeLibraryPathString, vc, pkgFlags);
                if (PackageManagerService.DEBUG_UPGRADE) Log.v(PackageManagerService.TAG, "Package "
                        + name + " is adopting original package " + origPackage.name);
                // Note that we will retain the new package's signature so
                // that we can keep its data.
                PackageSignatures s = p.signatures;
                p.copyFrom(origPackage);
                p.signatures = s;
                p.sharedUser = origPackage.sharedUser;
                p.appId = origPackage.appId;
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
                    if (DEBUG_STOPPED) {
                        RuntimeException e = new RuntimeException("here");
                        e.fillInStackTrace();
                        Slog.i(PackageManagerService.TAG, "Stopping package " + name, e);
                    }
                    List<UserInfo> users = getAllUsers();
                    if (users != null && allowInstall) {
                        for (UserInfo user : users) {
                            // By default we consider this app to be installed
                            // for the user if no user has been specified (which
                            // means to leave it at its original value, and the
                            // original default value is true), or we are being
                            // asked to install for all users, or this is the
                            // user we are installing for.
                            final boolean installed = installUser == null
                                    || installUser.getIdentifier() == UserHandle.USER_ALL
                                    || installUser.getIdentifier() == user.id;
                            p.setUserState(user.id, COMPONENT_ENABLED_STATE_DEFAULT,
                                    installed,
                                    true, // stopped,
                                    true, // notLaunched
                                    null, null);
                            writePackageRestrictionsLPr(user.id);
                        }
                    }
                }
                if (sharedUser != null) {
                    p.appId = sharedUser.userId;
                } else {
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
                        p.appId = dis.appId;
                        // Clone permissions
                        p.grantedPermissions = new HashSet<String>(dis.grantedPermissions);
                        // Clone component info
                        List<UserInfo> users = getAllUsers();
                        if (users != null) {
                            for (UserInfo user : users) {
                                int userId = user.id;
                                p.setDisabledComponentsCopy(
                                        dis.getDisabledComponents(userId), userId);
                                p.setEnabledComponentsCopy(
                                        dis.getEnabledComponents(userId), userId);
                            }
                        }
                        // Add new setting to list of user ids
                        addUserIdLPw(p.appId, p, name);
                    } else {
                        // Assign new user id
                        p.appId = newUserIdLPw(p);
                    }
                }
            }
            if (p.appId < 0) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Package " + name + " could not be assigned a valid uid");
                return null;
            }
            if (add) {
                // Finish adding new package by adding it and updating shared
                // user preferences
                addPackageSettingLPw(p, name, sharedUser);
            }
        } else {
            if (installUser != null && allowInstall) {
                // The caller has explicitly specified the user they want this
                // package installed for, and the package already exists.
                // Make sure it conforms to the new request.
                List<UserInfo> users = getAllUsers();
                if (users != null) {
                    for (UserInfo user : users) {
                        if (installUser.getIdentifier() == UserHandle.USER_ALL
                                || installUser.getIdentifier() == user.id) {
                            boolean installed = p.getInstalled(user.id);
                            if (!installed) {
                                p.setInstalled(true, user.id);
                                writePackageRestrictionsLPr(user.id);
                            }
                        }
                    }
                }
            }
        }
        return p;
    }

    void insertPackageSettingLPw(PackageSetting p, PackageParser.Package pkg) {
        p.pkg = pkg;
        // pkg.mSetEnabled = p.getEnabled(userId);
        // pkg.mSetStopped = p.getStopped(userId);
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
        // Update flags if needed.
        if (pkg.applicationInfo.flags != p.pkgFlags) {
            p.pkgFlags = pkg.applicationInfo.flags;
        }
        // If this app defines a shared user id initialize
        // the shared user signatures as well.
        if (p.sharedUser != null && p.sharedUser.signatures.mSignatures == null) {
            p.sharedUser.signatures.assignSignatures(pkg.mSignatures);
        }
        addPackageSettingLPw(p, pkg.packageName, p.sharedUser);
    }

    // Utility method that adds a PackageSetting to mPackages and
    // completes updating the shared user attributes
    private void addPackageSettingLPw(PackageSetting p, String name,
            SharedUserSetting sharedUser) {
        mPackages.put(name, p);
        if (sharedUser != null) {
            if (p.sharedUser != null && p.sharedUser != sharedUser) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                        "Package " + p.name + " was user "
                        + p.sharedUser + " but is now " + sharedUser
                        + "; I am not changing its files so it will probably fail!");
                p.sharedUser.packages.remove(p);
            } else if (p.appId != sharedUser.userId) {
                PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Package " + p.name + " was user id " + p.appId
                    + " but is now user " + sharedUser
                    + " with id " + sharedUser.userId
                    + "; I am not changing its files so it will probably fail!");
            }

            sharedUser.packages.add(p);
            p.sharedUser = sharedUser;
            p.appId = sharedUser.userId;
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
    void updateSharedUserPermsLPw(PackageSetting deletedPs, int[] globalGids) {
        if ((deletedPs == null) || (deletedPs.pkg == null)) {
            Slog.i(PackageManagerService.TAG,
                    "Trying to update info for null package. Just ignoring");
            return;
        }
        // No sharedUserId
        if (deletedPs.sharedUser == null) {
            return;
        }
        SharedUserSetting sus = deletedPs.sharedUser;
        // Update permissions
        for (String eachPerm : deletedPs.pkg.requestedPermissions) {
            boolean used = false;
            if (!sus.grantedPermissions.contains(eachPerm)) {
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

    int removePackageLPw(String name) {
        final PackageSetting p = mPackages.get(name);
        if (p != null) {
            mPackages.remove(name);
            if (p.sharedUser != null) {
                p.sharedUser.packages.remove(p);
                if (p.sharedUser.packages.size() == 0) {
                    mSharedUsers.remove(p.sharedUser.name);
                    removeUserIdLPw(p.sharedUser.userId);
                    return p.sharedUser.userId;
                }
            } else {
                removeUserIdLPw(p.appId);
                return p.appId;
            }
        }
        return -1;
    }

    private void replacePackageLPw(String name, PackageSetting newp) {
        final PackageSetting p = mPackages.get(name);
        if (p != null) {
            if (p.sharedUser != null) {
                p.sharedUser.packages.remove(p);
                p.sharedUser.packages.add(newp);
            } else {
                replaceUserIdLPw(p.appId, newp);
            }
        }
        mPackages.put(name, newp);
    }

    private boolean addUserIdLPw(int uid, Object obj, Object name) {
        if (uid > Process.LAST_APPLICATION_UID) {
            return false;
        }

        if (uid >= Process.FIRST_APPLICATION_UID) {
            int N = mUserIds.size();
            final int index = uid - Process.FIRST_APPLICATION_UID;
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

    public Object getUserIdLPr(int uid) {
        if (uid >= Process.FIRST_APPLICATION_UID) {
            final int N = mUserIds.size();
            final int index = uid - Process.FIRST_APPLICATION_UID;
            return index < N ? mUserIds.get(index) : null;
        } else {
            return mOtherUserIds.get(uid);
        }
    }

    private void removeUserIdLPw(int uid) {
        if (uid >= Process.FIRST_APPLICATION_UID) {
            final int N = mUserIds.size();
            final int index = uid - Process.FIRST_APPLICATION_UID;
            if (index < N) mUserIds.set(index, null);
        } else {
            mOtherUserIds.remove(uid);
        }
    }

    private void replaceUserIdLPw(int uid, Object obj) {
        if (uid >= Process.FIRST_APPLICATION_UID) {
            final int N = mUserIds.size();
            final int index = uid - Process.FIRST_APPLICATION_UID;
            if (index < N) mUserIds.set(index, obj);
        } else {
            mOtherUserIds.put(uid, obj);
        }
    }

    PreferredIntentResolver editPreferredActivitiesLPw(int userId) {
        PreferredIntentResolver pir = mPreferredActivities.get(userId);
        if (pir == null) {
            pir = new PreferredIntentResolver();
            mPreferredActivities.put(userId, pir);
        }
        return pir;
    }

    private File getUserPackagesStateFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), "package-restrictions.xml");
    }

    private File getUserPackagesStateBackupFile(int userId) {
        return new File(Environment.getUserSystemDirectory(userId),
                "package-restrictions-backup.xml");
    }

    void writeAllUsersPackageRestrictionsLPr() {
        List<UserInfo> users = getAllUsers();
        if (users == null) return;

        for (UserInfo user : users) {
            writePackageRestrictionsLPr(user.id);
        }
    }

    void readAllUsersPackageRestrictionsLPr() {
        List<UserInfo> users = getAllUsers();
        if (users == null) {
            readPackageRestrictionsLPr(0);
            return;
        }

        for (UserInfo user : users) {
            readPackageRestrictionsLPr(user.id);
        }
    }

    private void readPreferredActivitiesLPw(XmlPullParser parser, int userId)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                PreferredActivity pa = new PreferredActivity(parser);
                if (pa.mPref.getParseError() == null) {
                    editPreferredActivitiesLPw(userId).addFilter(pa);
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

    void readPackageRestrictionsLPr(int userId) {
        if (DEBUG_MU) {
            Log.i(TAG, "Reading package restrictions for user=" + userId);
        }
        FileInputStream str = null;
        File userPackagesStateFile = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        if (backupFile.exists()) {
            try {
                str = new FileInputStream(backupFile);
                mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup stopped packages file");
                if (userPackagesStateFile.exists()) {
                    // If both the backup and normal file exist, we
                    // ignore the normal one since it might have been
                    // corrupted.
                    Slog.w(PackageManagerService.TAG, "Cleaning up stopped packages file "
                            + userPackagesStateFile);
                    userPackagesStateFile.delete();
                }
            } catch (java.io.IOException e) {
                // We'll try for the normal settings file.
            }
        }

        try {
            if (str == null) {
                if (!userPackagesStateFile.exists()) {
                    mReadMessages.append("No stopped packages file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No stopped packages file; "
                            + "assuming all started");
                    // At first boot, make sure no packages are stopped.
                    // We usually want to have third party apps initialize
                    // in the stopped state, but not at first boot.  Also
                    // consider all applications to be installed.
                    for (PackageSetting pkg : mPackages.values()) {
                        pkg.setUserState(userId, COMPONENT_ENABLED_STATE_DEFAULT,
                                true,   // installed
                                false,  // stopped
                                false,  // notLaunched
                                null, null);
                    }
                    return;
                }
                str = new FileInputStream(userPackagesStateFile);
            }
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(str, null);

            int type;
            while ((type=parser.next()) != XmlPullParser.START_TAG
                       && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                mReadMessages.append("No start tag found in package restrictions file\n");
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "No start tag found in package manager stopped packages");
                return;
            }

            int outerDepth = parser.getDepth();
            PackageSetting ps = null;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG
                           || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG
                        || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals(TAG_PACKAGE)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    ps = mPackages.get(name);
                    if (ps == null) {
                        Slog.w(PackageManagerService.TAG, "No package known for stopped package: "
                                + name);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    final String enabledStr = parser.getAttributeValue(null, ATTR_ENABLED);
                    final int enabled = enabledStr == null
                            ? COMPONENT_ENABLED_STATE_DEFAULT : Integer.parseInt(enabledStr);
                    final String installedStr = parser.getAttributeValue(null, ATTR_INSTALLED);
                    final boolean installed = installedStr == null
                            ? true : Boolean.parseBoolean(installedStr);
                    final String stoppedStr = parser.getAttributeValue(null, ATTR_STOPPED);
                    final boolean stopped = stoppedStr == null
                            ? false : Boolean.parseBoolean(stoppedStr);
                    final String notLaunchedStr = parser.getAttributeValue(null, ATTR_NOT_LAUNCHED);
                    final boolean notLaunched = stoppedStr == null
                            ? false : Boolean.parseBoolean(notLaunchedStr);

                    HashSet<String> enabledComponents = null;
                    HashSet<String> disabledComponents = null;

                    int packageDepth = parser.getDepth();
                    while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG
                            || parser.getDepth() > packageDepth)) {
                        if (type == XmlPullParser.END_TAG
                                || type == XmlPullParser.TEXT) {
                            continue;
                        }
                        tagName = parser.getName();
                        if (tagName.equals(TAG_ENABLED_COMPONENTS)) {
                            enabledComponents = readComponentsLPr(parser);
                        } else if (tagName.equals(TAG_DISABLED_COMPONENTS)) {
                            disabledComponents = readComponentsLPr(parser);
                        }
                    }

                    ps.setUserState(userId, enabled, installed, stopped, notLaunched,
                            enabledComponents, disabledComponents);
                } else if (tagName.equals("preferred-activities")) {
                    readPreferredActivitiesLPw(parser, userId);
                } else {
                    Slog.w(PackageManagerService.TAG, "Unknown element under <stopped-packages>: "
                          + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

            str.close();

        } catch (XmlPullParserException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Error reading stopped packages: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages", e);

        } catch (java.io.IOException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages", e);
        }
    }

    private HashSet<String> readComponentsLPr(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        HashSet<String> components = null;
        int type;
        int outerDepth = parser.getDepth();
        String tagName;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }
            tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String componentName = parser.getAttributeValue(null, ATTR_NAME);
                if (componentName != null) {
                    if (components == null) {
                        components = new HashSet<String>();
                    }
                    components.add(componentName);
                }
            }
        }
        return components;
    }

    void writePreferredActivitiesLPr(XmlSerializer serializer, int userId)
            throws IllegalArgumentException, IllegalStateException, IOException {
        serializer.startTag(null, "preferred-activities");
        PreferredIntentResolver pir = mPreferredActivities.get(userId);
        if (pir != null) {
            for (final PreferredActivity pa : pir.filterSet()) {
                serializer.startTag(null, TAG_ITEM);
                pa.writeToXml(serializer);
                serializer.endTag(null, TAG_ITEM);
            }
        }
        serializer.endTag(null, "preferred-activities");
    }

    void writePackageRestrictionsLPr(int userId) {
        if (DEBUG_MU) {
            Log.i(TAG, "Writing package restrictions for user=" + userId);
        }
        // Keep the old stopped packages around until we know the new ones have
        // been successfully written.
        File userPackagesStateFile = getUserPackagesStateFile(userId);
        File backupFile = getUserPackagesStateBackupFile(userId);
        new File(userPackagesStateFile.getParent()).mkdirs();
        if (userPackagesStateFile.exists()) {
            // Presence of backup settings file indicates that we failed
            // to persist packages earlier. So preserve the older
            // backup for future reference since the current packages
            // might have been corrupted.
            if (!backupFile.exists()) {
                if (!userPackagesStateFile.renameTo(backupFile)) {
                    Log.wtf(PackageManagerService.TAG, "Unable to backup user packages state file, "
                            + "current changes will be lost at reboot");
                    return;
                }
            } else {
                userPackagesStateFile.delete();
                Slog.w(PackageManagerService.TAG, "Preserving older stopped packages backup");
            }
        }

        try {
            final FileOutputStream fstr = new FileOutputStream(userPackagesStateFile);
            final BufferedOutputStream str = new BufferedOutputStream(fstr);

            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(str, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_PACKAGE_RESTRICTIONS);

            for (final PackageSetting pkg : mPackages.values()) {
                PackageUserState ustate = pkg.readUserState(userId);
                if (ustate.stopped || ustate.notLaunched || !ustate.installed
                        || ustate.enabled != COMPONENT_ENABLED_STATE_DEFAULT
                        || (ustate.enabledComponents != null
                                && ustate.enabledComponents.size() > 0)
                        || (ustate.disabledComponents != null
                                && ustate.disabledComponents.size() > 0)) {
                    serializer.startTag(null, TAG_PACKAGE);
                    serializer.attribute(null, ATTR_NAME, pkg.name);
                    if (DEBUG_MU) Log.i(TAG, "  pkg=" + pkg.name + ", state=" + ustate.enabled);

                    if (!ustate.installed) {
                        serializer.attribute(null, ATTR_INSTALLED, "false");
                    }
                    if (ustate.stopped) {
                        serializer.attribute(null, ATTR_STOPPED, "true");
                    }
                    if (ustate.notLaunched) {
                        serializer.attribute(null, ATTR_NOT_LAUNCHED, "true");
                    }
                    if (ustate.enabled != COMPONENT_ENABLED_STATE_DEFAULT) {
                        serializer.attribute(null, ATTR_ENABLED,
                                Integer.toString(ustate.enabled));
                    }
                    if (ustate.enabledComponents != null
                            && ustate.enabledComponents.size() > 0) {
                        serializer.startTag(null, TAG_ENABLED_COMPONENTS);
                        for (final String name : ustate.enabledComponents) {
                            serializer.startTag(null, TAG_ITEM);
                            serializer.attribute(null, ATTR_NAME, name);
                            serializer.endTag(null, TAG_ITEM);
                        }
                        serializer.endTag(null, TAG_ENABLED_COMPONENTS);
                    }
                    if (ustate.disabledComponents != null
                            && ustate.disabledComponents.size() > 0) {
                        serializer.startTag(null, TAG_DISABLED_COMPONENTS);
                        for (final String name : ustate.disabledComponents) {
                            serializer.startTag(null, TAG_ITEM);
                            serializer.attribute(null, ATTR_NAME, name);
                            serializer.endTag(null, TAG_ITEM);
                        }
                        serializer.endTag(null, TAG_DISABLED_COMPONENTS);
                    }
                    serializer.endTag(null, TAG_PACKAGE);
                }
            }

            writePreferredActivitiesLPr(serializer, userId);

            serializer.endTag(null, TAG_PACKAGE_RESTRICTIONS);

            serializer.endDocument();

            str.flush();
            FileUtils.sync(fstr);
            str.close();

            // New settings successfully written, old ones are no longer
            // needed.
            backupFile.delete();
            FileUtils.setPermissions(userPackagesStateFile.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP,
                    -1, -1);

            // Done, all is good!
            return;
        } catch(java.io.IOException e) {
            Log.wtf(PackageManagerService.TAG,
                    "Unable to write package manager user packages state, "
                    + " current changes will be lost at reboot", e);
        }

        // Clean up partially written files
        if (userPackagesStateFile.exists()) {
            if (!userPackagesStateFile.delete()) {
                Log.i(PackageManagerService.TAG, "Failed to clean up mangled file: "
                        + mStoppedPackagesFilename);
            }
        }
    }

    // Note: assumed "stopped" field is already cleared in all packages.
    // Legacy reader, used to read in the old file format after an upgrade. Not used after that.
    void readStoppedLPw() {
        FileInputStream str = null;
        if (mBackupStoppedPackagesFilename.exists()) {
            try {
                str = new FileInputStream(mBackupStoppedPackagesFilename);
                mReadMessages.append("Reading from backup stopped packages file\n");
                PackageManagerService.reportSettingsProblem(Log.INFO,
                        "Need to read from backup stopped packages file");
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
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No stopped packages file file; assuming all started");
                    // At first boot, make sure no packages are stopped.
                    // We usually want to have third party apps initialize
                    // in the stopped state, but not at first boot.
                    for (PackageSetting pkg : mPackages.values()) {
                        pkg.setStopped(false, 0);
                        pkg.setNotLaunched(false, 0);
                    }
                    return;
                }
                str = new FileInputStream(mStoppedPackagesFilename);
            }
            final XmlPullParser parser = Xml.newPullParser();
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
                if (tagName.equals(TAG_PACKAGE)) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    PackageSetting ps = mPackages.get(name);
                    if (ps != null) {
                        ps.setStopped(true, 0);
                        if ("1".equals(parser.getAttributeValue(null, ATTR_NOT_LAUNCHED))) {
                            ps.setNotLaunched(true, 0);
                        }
                    } else {
                        Slog.w(PackageManagerService.TAG,
                                "No package known for stopped package: " + name);
                    }
                    XmlUtils.skipCurrentTag(parser);
                } else {
                    Slog.w(PackageManagerService.TAG, "Unknown element under <stopped-packages>: "
                          + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }

            str.close();

        } catch (XmlPullParserException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR,
                    "Error reading stopped packages: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages", e);

        } catch (java.io.IOException e) {
            mReadMessages.append("Error reading: " + e.toString());
            PackageManagerService.reportSettingsProblem(Log.ERROR, "Error reading settings: " + e);
            Log.wtf(PackageManagerService.TAG, "Error reading package manager stopped packages", e);

        }
    }

    void writeLPr() {
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

            if (mVerifierDeviceIdentity != null) {
                serializer.startTag(null, "verifier");
                serializer.attribute(null, "device", mVerifierDeviceIdentity.toString());
                serializer.endTag(null, "verifier");
            }

            if (mReadExternalStorageEnforced != null) {
                serializer.startTag(null, TAG_READ_EXTERNAL_STORAGE);
                serializer.attribute(
                        null, ATTR_ENFORCEMENT, mReadExternalStorageEnforced ? "1" : "0");
                serializer.endTag(null, TAG_READ_EXTERNAL_STORAGE);
            }

            serializer.startTag(null, "permission-trees");
            for (BasePermission bp : mPermissionTrees.values()) {
                writePermissionLPr(serializer, bp);
            }
            serializer.endTag(null, "permission-trees");

            serializer.startTag(null, "permissions");
            for (BasePermission bp : mPermissions.values()) {
                writePermissionLPr(serializer, bp);
            }
            serializer.endTag(null, "permissions");

            for (final PackageSetting pkg : mPackages.values()) {
                writePackageLPr(serializer, pkg);
            }

            for (final PackageSetting pkg : mDisabledSysPackages.values()) {
                writeDisabledSysPackageLPr(serializer, pkg);
            }

            for (final SharedUserSetting usr : mSharedUsers.values()) {
                serializer.startTag(null, "shared-user");
                serializer.attribute(null, ATTR_NAME, usr.name);
                serializer.attribute(null, "userId",
                        Integer.toString(usr.userId));
                usr.signatures.writeXml(serializer, "sigs", mPastSignatures);
                serializer.startTag(null, "perms");
                for (String name : usr.grantedPermissions) {
                    serializer.startTag(null, TAG_ITEM);
                    serializer.attribute(null, ATTR_NAME, name);
                    serializer.endTag(null, TAG_ITEM);
                }
                serializer.endTag(null, "perms");
                serializer.endTag(null, "shared-user");
            }

            if (mPackagesToBeCleaned.size() > 0) {
                for (PackageCleanItem item : mPackagesToBeCleaned) {
                    final String userStr = Integer.toString(item.userId);
                    serializer.startTag(null, "cleaning-package");
                    serializer.attribute(null, ATTR_NAME, item.packageName);
                    serializer.attribute(null, ATTR_CODE, item.andCode ? "true" : "false");
                    serializer.attribute(null, ATTR_USER, userStr);
                    serializer.endTag(null, "cleaning-package");
                }
            }
            
            if (mRenamedPackages.size() > 0) {
                for (Map.Entry<String, String> e : mRenamedPackages.entrySet()) {
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
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP,
                    -1, -1);

            // Write package list file now, use a JournaledFile.
            //
            File tempFile = new File(mPackageListFilename.toString() + ".tmp");
            JournaledFile journal = new JournaledFile(mPackageListFilename, tempFile);

            fstr = new FileOutputStream(journal.chooseForWrite());
            str = new BufferedOutputStream(fstr);
            try {
                StringBuilder sb = new StringBuilder();
                for (final PackageSetting pkg : mPackages.values()) {
                    ApplicationInfo ai = pkg.pkg.applicationInfo;
                    String dataPath = ai.dataDir;
                    boolean isDebug  = (ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;

                    // Avoid any application that has a space in its path
                    // or that is handled by the system.
                    if (dataPath.indexOf(" ") >= 0 || ai.uid < Process.FIRST_APPLICATION_UID)
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
            } catch (Exception e) {
                IoUtils.closeQuietly(str);
                journal.rollback();
            }

            FileUtils.setPermissions(mPackageListFilename.toString(),
                    FileUtils.S_IRUSR|FileUtils.S_IWUSR
                    |FileUtils.S_IRGRP|FileUtils.S_IWGRP,
                    -1, -1);

            writeAllUsersPackageRestrictionsLPr();
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
                Log.wtf(PackageManagerService.TAG, "Failed to clean up mangled file: "
                        + mSettingsFilename);
            }
        }
        //Debug.stopMethodTracing();
    }

    void writeDisabledSysPackageLPr(XmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "updated-package");
        serializer.attribute(null, ATTR_NAME, pkg.name);
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
            serializer.attribute(null, "userId", Integer.toString(pkg.appId));
        } else {
            serializer.attribute(null, "sharedUserId", Integer.toString(pkg.appId));
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
                    serializer.startTag(null, TAG_ITEM);
                    serializer.attribute(null, ATTR_NAME, name);
                    serializer.endTag(null, TAG_ITEM);
                }
            }
        }
        serializer.endTag(null, "perms");
        serializer.endTag(null, "updated-package");
    }

    void writePackageLPr(XmlSerializer serializer, final PackageSetting pkg)
            throws java.io.IOException {
        serializer.startTag(null, "package");
        serializer.attribute(null, ATTR_NAME, pkg.name);
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
            serializer.attribute(null, "userId", Integer.toString(pkg.appId));
        } else {
            serializer.attribute(null, "sharedUserId", Integer.toString(pkg.appId));
        }
        if (pkg.uidError) {
            serializer.attribute(null, "uidError", "true");
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
                    serializer.startTag(null, TAG_ITEM);
                    serializer.attribute(null, ATTR_NAME, name);
                    serializer.endTag(null, TAG_ITEM);
                }
            }
            serializer.endTag(null, "perms");
        }

        serializer.endTag(null, "package");
    }

    void writePermissionLPr(XmlSerializer serializer, BasePermission bp)
            throws XmlPullParserException, java.io.IOException {
        if (bp.type != BasePermission.TYPE_BUILTIN && bp.sourcePackage != null) {
            serializer.startTag(null, TAG_ITEM);
            serializer.attribute(null, ATTR_NAME, bp.name);
            serializer.attribute(null, "package", bp.sourcePackage);
            if (bp.protectionLevel != PermissionInfo.PROTECTION_NORMAL) {
                serializer.attribute(null, "protection", Integer.toString(bp.protectionLevel));
            }
            if (PackageManagerService.DEBUG_SETTINGS)
                Log.v(PackageManagerService.TAG, "Writing perm: name=" + bp.name + " type="
                        + bp.type);
            if (bp.type == BasePermission.TYPE_DYNAMIC) {
                final PermissionInfo pi = bp.perm != null ? bp.perm.info : bp.pendingInfo;
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
            serializer.endTag(null, TAG_ITEM);
        }
    }

    ArrayList<PackageSetting> getListOfIncompleteInstallPackagesLPr() {
        final HashSet<String> kList = new HashSet<String>(mPackages.keySet());
        final Iterator<String> its = kList.iterator();
        final ArrayList<PackageSetting> ret = new ArrayList<PackageSetting>();
        while (its.hasNext()) {
            final String key = its.next();
            final PackageSetting ps = mPackages.get(key);
            if (ps.getInstallStatus() == PackageSettingBase.PKG_INSTALL_INCOMPLETE) {
                ret.add(ps);
            }
        }
        return ret;
    }

    void addPackageToCleanLPw(PackageCleanItem pkg) {
        if (!mPackagesToBeCleaned.contains(pkg)) {
            mPackagesToBeCleaned.add(pkg);
        }
    }

    boolean readLPw(List<UserInfo> users) {
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

        mPendingPackages.clear();
        mPastSignatures.clear();

        try {
            if (str == null) {
                if (!mSettingsFilename.exists()) {
                    mReadMessages.append("No settings file found\n");
                    PackageManagerService.reportSettingsProblem(Log.INFO,
                            "No settings file; creating initial state");
                    readDefaultPreferredAppsLPw(0);
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
                Log.wtf(PackageManagerService.TAG,
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
                    readPackageLPw(parser);
                } else if (tagName.equals("permissions")) {
                    readPermissionsLPw(mPermissions, parser);
                } else if (tagName.equals("permission-trees")) {
                    readPermissionsLPw(mPermissionTrees, parser);
                } else if (tagName.equals("shared-user")) {
                    readSharedUserLPw(parser);
                } else if (tagName.equals("preferred-packages")) {
                    // no longer used.
                } else if (tagName.equals("preferred-activities")) {
                    // Upgrading from old single-user implementation;
                    // these are the preferred activities for user 0.
                    readPreferredActivitiesLPw(parser, 0);
                } else if (tagName.equals("updated-package")) {
                    readDisabledSysPackageLPw(parser);
                } else if (tagName.equals("cleaning-package")) {
                    String name = parser.getAttributeValue(null, ATTR_NAME);
                    String userStr = parser.getAttributeValue(null, ATTR_USER);
                    String codeStr = parser.getAttributeValue(null, ATTR_CODE);
                    if (name != null) {
                        int userId = 0;
                        boolean andCode = true;
                        try {
                            if (userStr != null) {
                                userId = Integer.parseInt(userStr);
                            }
                        } catch (NumberFormatException e) {
                        }
                        if (codeStr != null) {
                            andCode = Boolean.parseBoolean(codeStr);
                        }
                        addPackageToCleanLPw(new PackageCleanItem(userId, name, andCode));
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
                } else if (tagName.equals("verifier")) {
                    final String deviceIdentity = parser.getAttributeValue(null, "device");
                    try {
                        mVerifierDeviceIdentity = VerifierDeviceIdentity.parse(deviceIdentity);
                    } catch (IllegalArgumentException e) {
                        Slog.w(PackageManagerService.TAG, "Discard invalid verifier device id: "
                                + e.getMessage());
                    }
                } else if (TAG_READ_EXTERNAL_STORAGE.equals(tagName)) {
                    final String enforcement = parser.getAttributeValue(null, ATTR_ENFORCEMENT);
                    mReadExternalStorageEnforced = "1".equals(enforcement);
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
            Object idObj = getUserIdLPr(pp.sharedId);
            if (idObj != null && idObj instanceof SharedUserSetting) {
                PackageSetting p = getPackageLPw(pp.name, null, pp.realName,
                        (SharedUserSetting) idObj, pp.codePath, pp.resourcePath,
                        pp.nativeLibraryPathString, pp.versionCode, pp.pkgFlags,
                        null, true /* add */, false /* allowInstall */);
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

        if (mBackupStoppedPackagesFilename.exists()
                || mStoppedPackagesFilename.exists()) {
            // Read old file
            readStoppedLPw();
            mBackupStoppedPackagesFilename.delete();
            mStoppedPackagesFilename.delete();
            // Migrate to new file format
            writePackageRestrictionsLPr(0);
        } else {
            if (users == null) {
                readPackageRestrictionsLPr(0);
            } else {
                for (UserInfo user : users) {
                    readPackageRestrictionsLPr(user.id);
                }
            }
        }

        /*
         * Make sure all the updated system packages have their shared users
         * associated with them.
         */
        final Iterator<PackageSetting> disabledIt = mDisabledSysPackages.values().iterator();
        while (disabledIt.hasNext()) {
            final PackageSetting disabledPs = disabledIt.next();
            final Object id = getUserIdLPr(disabledPs.appId);
            if (id != null && id instanceof SharedUserSetting) {
                disabledPs.sharedUser = (SharedUserSetting) id;
            }
        }

        mReadMessages.append("Read completed successfully: " + mPackages.size() + " packages, "
                + mSharedUsers.size() + " shared uids\n");

        return true;
    }

    private void readDefaultPreferredAppsLPw(int userId) {
        // Read preferred apps from .../etc/preferred-apps directory.
        File preferredDir = new File(Environment.getRootDirectory(), "etc/preferred-apps");
        if (!preferredDir.exists() || !preferredDir.isDirectory()) {
            return;
        }
        if (!preferredDir.canRead()) {
            Slog.w(TAG, "Directory " + preferredDir + " cannot be read");
            return;
        }

        // Iterate over the files in the directory and scan .xml files
        for (File f : preferredDir.listFiles()) {
            if (!f.getPath().endsWith(".xml")) {
                Slog.i(TAG, "Non-xml file " + f + " in " + preferredDir + " directory, ignoring");
                continue;
            }
            if (!f.canRead()) {
                Slog.w(TAG, "Preferred apps file " + f + " cannot be read");
                continue;
            }

            FileInputStream str = null;
            try {
                str = new FileInputStream(f);
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(str, null);

                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    ;
                }

                if (type != XmlPullParser.START_TAG) {
                    Slog.w(TAG, "Preferred apps file " + f + " does not have start tag");
                    continue;
                }
                if (!"preferred-activities".equals(parser.getName())) {
                    Slog.w(TAG, "Preferred apps file " + f
                            + " does not start with 'preferred-activities'");
                    continue;
                }
                readPreferredActivitiesLPw(parser, userId);
            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Error reading apps file " + f, e);
            } catch (IOException e) {
                Slog.w(TAG, "Error reading apps file " + f, e);
            } finally {
                if (str != null) {
                    try {
                        str.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
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

    private void readPermissionsLPw(HashMap<String, BasePermission> out, XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            final String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                final String name = parser.getAttributeValue(null, ATTR_NAME);
                final String sourcePackage = parser.getAttributeValue(null, "package");
                final String ptype = parser.getAttributeValue(null, "type");
                if (name != null && sourcePackage != null) {
                    final boolean dynamic = "dynamic".equals(ptype);
                    final BasePermission bp = new BasePermission(name, sourcePackage,
                            dynamic ? BasePermission.TYPE_DYNAMIC : BasePermission.TYPE_NORMAL);
                    bp.protectionLevel = readInt(parser, null, "protection",
                            PermissionInfo.PROTECTION_NORMAL);
                    bp.protectionLevel = PermissionInfo.fixProtectionLevel(bp.protectionLevel);
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

    private void readDisabledSysPackageLPw(XmlPullParser parser) throws XmlPullParserException,
            IOException {
        String name = parser.getAttributeValue(null, ATTR_NAME);
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
        ps.appId = idStr != null ? Integer.parseInt(idStr) : 0;
        if (ps.appId <= 0) {
            String sharedIdStr = parser.getAttributeValue(null, "sharedUserId");
            ps.appId = sharedIdStr != null ? Integer.parseInt(sharedIdStr) : 0;
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
                readGrantedPermissionsLPw(parser, ps.grantedPermissions);
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <updated-package>: " + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        mDisabledSysPackages.put(name, ps);
    }

    private void readPackageLPw(XmlPullParser parser) throws XmlPullParserException, IOException {
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
            name = parser.getAttributeValue(null, ATTR_NAME);
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
                packageSetting = addPackageLPw(name.intern(), realName, new File(codePathStr),
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
            // Handle legacy string here for single-user mode
            final String enabledStr = parser.getAttributeValue(null, ATTR_ENABLED);
            if (enabledStr != null) {
                try {
                    packageSetting.setEnabled(Integer.parseInt(enabledStr), 0 /* userId */);
                } catch (NumberFormatException e) {
                    if (enabledStr.equalsIgnoreCase("true")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 0);
                    } else if (enabledStr.equalsIgnoreCase("false")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0);
                    } else if (enabledStr.equalsIgnoreCase("default")) {
                        packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0);
                    } else {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: package " + name
                                        + " has bad enabled value: " + idStr + " at "
                                        + parser.getPositionDescription());
                    }
                }
            } else {
                packageSetting.setEnabled(COMPONENT_ENABLED_STATE_DEFAULT, 0);
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
                // Legacy 
                if (tagName.equals(TAG_DISABLED_COMPONENTS)) {
                    readDisabledComponentsLPw(packageSetting, parser, 0);
                } else if (tagName.equals(TAG_ENABLED_COMPONENTS)) {
                    readEnabledComponentsLPw(packageSetting, parser, 0);
                } else if (tagName.equals("sigs")) {
                    packageSetting.signatures.readXml(parser, mPastSignatures);
                } else if (tagName.equals("perms")) {
                    readGrantedPermissionsLPw(parser, packageSetting.grantedPermissions);
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

    private void readDisabledComponentsLPw(PackageSettingBase packageSetting, XmlPullParser parser,
            int userId) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name != null) {
                    packageSetting.addDisabledComponent(name.intern(), userId);
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

    private void readEnabledComponentsLPw(PackageSettingBase packageSetting, XmlPullParser parser,
            int userId) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                if (name != null) {
                    packageSetting.addEnabledComponent(name.intern(), userId);
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

    private void readSharedUserLPw(XmlPullParser parser) throws XmlPullParserException,IOException {
        String name = null;
        String idStr = null;
        int pkgFlags = 0;
        SharedUserSetting su = null;
        try {
            name = parser.getAttributeValue(null, ATTR_NAME);
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
                if ((su = addSharedUserLPw(name.intern(), userId, pkgFlags)) == null) {
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
                    readGrantedPermissionsLPw(parser, su.grantedPermissions);
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

    private void readGrantedPermissionsLPw(XmlPullParser parser, HashSet<String> outPerms)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_ITEM)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
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

    void createNewUserLILPw(Installer installer, int userHandle, File path) {
        path.mkdir();
        FileUtils.setPermissions(path.toString(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                | FileUtils.S_IXOTH, -1, -1);
        for (PackageSetting ps : mPackages.values()) {
            // Only system apps are initially installed.
            ps.setInstalled((ps.pkgFlags&ApplicationInfo.FLAG_SYSTEM) != 0, userHandle);
            // Need to create a data directory for all apps under this user.
            installer.createUserData(ps.name,
                    UserHandle.getUid(userHandle, ps.appId), userHandle);
        }
        readDefaultPreferredAppsLPw(userHandle);
        writePackageRestrictionsLPr(userHandle);
    }

    void removeUserLPr(int userId) {
        Set<Entry<String, PackageSetting>> entries = mPackages.entrySet();
        for (Entry<String, PackageSetting> entry : entries) {
            entry.getValue().removeUser(userId);
        }
        mPreferredActivities.remove(userId);
        File file = getUserPackagesStateFile(userId);
        file.delete();
        file = getUserPackagesStateBackupFile(userId);
        file.delete();
    }

    // Returns -1 if we could not find an available UserId to assign
    private int newUserIdLPw(Object obj) {
        // Let's be stupidly inefficient for now...
        final int N = mUserIds.size();
        for (int i = 0; i < N; i++) {
            if (mUserIds.get(i) == null) {
                mUserIds.set(i, obj);
                return Process.FIRST_APPLICATION_UID + i;
            }
        }

        // None left?
        if (N > (Process.LAST_APPLICATION_UID-Process.FIRST_APPLICATION_UID)) {
            return -1;
        }

        mUserIds.add(obj);
        return Process.FIRST_APPLICATION_UID + N;
    }

    public VerifierDeviceIdentity getVerifierDeviceIdentityLPw() {
        if (mVerifierDeviceIdentity == null) {
            mVerifierDeviceIdentity = VerifierDeviceIdentity.generate();

            writeLPr();
        }

        return mVerifierDeviceIdentity;
    }

    public PackageSetting getDisabledSystemPkgLPr(String name) {
        PackageSetting ps = mDisabledSysPackages.get(name);
        return ps;
    }

    private String compToString(HashSet<String> cmp) {
        return cmp != null ? Arrays.toString(cmp.toArray()) : "[]";
    }
 
    boolean isEnabledLPr(ComponentInfo componentInfo, int flags, int userId) {
        if ((flags&PackageManager.GET_DISABLED_COMPONENTS) != 0) {
            return true;
        }
        final String pkgName = componentInfo.packageName;
        final PackageSetting packageSettings = mPackages.get(pkgName);
        if (PackageManagerService.DEBUG_SETTINGS) {
            Log.v(PackageManagerService.TAG, "isEnabledLock - packageName = "
                    + componentInfo.packageName + " componentName = " + componentInfo.name);
            Log.v(PackageManagerService.TAG, "enabledComponents: "
                    + compToString(packageSettings.getEnabledComponents(userId)));
            Log.v(PackageManagerService.TAG, "disabledComponents: "
                    + compToString(packageSettings.getDisabledComponents(userId)));
        }
        if (packageSettings == null) {
            return false;
        }
        PackageUserState ustate = packageSettings.readUserState(userId);
        if (ustate.enabled == COMPONENT_ENABLED_STATE_DISABLED
                || ustate.enabled == COMPONENT_ENABLED_STATE_DISABLED_USER
                || (packageSettings.pkg != null && !packageSettings.pkg.applicationInfo.enabled
                    && ustate.enabled == COMPONENT_ENABLED_STATE_DEFAULT)) {
            return false;
        }
        if (ustate.enabledComponents != null
                && ustate.enabledComponents.contains(componentInfo.name)) {
            return true;
        }
        if (ustate.disabledComponents != null
                && ustate.disabledComponents.contains(componentInfo.name)) {
            return false;
        }
        return componentInfo.enabled;
    }

    String getInstallerPackageNameLPr(String packageName) {
        final PackageSetting pkg = mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkg.installerPackageName;
    }

    int getApplicationEnabledSettingLPr(String packageName, int userId) {
        final PackageSetting pkg = mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        return pkg.getEnabled(userId);
    }

    int getComponentEnabledSettingLPr(ComponentName componentName, int userId) {
        final String packageName = componentName.getPackageName();
        final PackageSetting pkg = mPackages.get(packageName);
        if (pkg == null) {
            throw new IllegalArgumentException("Unknown component: " + componentName);
        }
        final String classNameStr = componentName.getClassName();
        return pkg.getCurrentEnabledStateLPr(classNameStr, userId);
    }

    boolean setPackageStoppedStateLPw(String packageName, boolean stopped,
            boolean allowedByPermission, int uid, int userId) {
        int appId = UserHandle.getAppId(uid);
        final PackageSetting pkgSetting = mPackages.get(packageName);
        if (pkgSetting == null) {
            throw new IllegalArgumentException("Unknown package: " + packageName);
        }
        if (!allowedByPermission && (appId != pkgSetting.appId)) {
            throw new SecurityException(
                    "Permission Denial: attempt to change stopped state from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + uid + ", package uid=" + pkgSetting.appId);
        }
        if (DEBUG_STOPPED) {
            if (stopped) {
                RuntimeException e = new RuntimeException("here");
                e.fillInStackTrace();
                Slog.i(TAG, "Stopping package " + packageName, e);
            }
        }
        if (pkgSetting.getStopped(userId) != stopped) {
            pkgSetting.setStopped(stopped, userId);
            // pkgSetting.pkg.mSetStopped = stopped;
            if (pkgSetting.getNotLaunched(userId)) {
                if (pkgSetting.installerPackageName != null) {
                    PackageManagerService.sendPackageBroadcast(Intent.ACTION_PACKAGE_FIRST_LAUNCH,
                            pkgSetting.name, null,
                            pkgSetting.installerPackageName, null, new int[] {userId});
                }
                pkgSetting.setNotLaunched(false, userId);
            }
            return true;
        }
        return false;
    }

    private List<UserInfo> getAllUsers() {
        long id = Binder.clearCallingIdentity();
        try {
            return UserManagerService.getInstance().getUsers(false);
        } catch (NullPointerException npe) {
            // packagemanager not yet initialized
        } finally {
            Binder.restoreCallingIdentity(id);
        }
        return null;
    }

    static final void printFlags(PrintWriter pw, int val, Object[] spec) {
        pw.print("[ ");
        for (int i=0; i<spec.length; i+=2) {
            int mask = (Integer)spec[i];
            if ((val & mask) != 0) {
                pw.print(spec[i+1]);
                pw.print(" ");
            }
        }
        pw.print("]");
    }

    static final Object[] FLAG_DUMP_SPEC = new Object[] {
        ApplicationInfo.FLAG_SYSTEM, "SYSTEM",
        ApplicationInfo.FLAG_DEBUGGABLE, "DEBUGGABLE",
        ApplicationInfo.FLAG_HAS_CODE, "HAS_CODE",
        ApplicationInfo.FLAG_PERSISTENT, "PERSISTENT",
        ApplicationInfo.FLAG_FACTORY_TEST, "FACTORY_TEST",
        ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING, "ALLOW_TASK_REPARENTING",
        ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA, "ALLOW_CLEAR_USER_DATA",
        ApplicationInfo.FLAG_UPDATED_SYSTEM_APP, "UPDATED_SYSTEM_APP",
        ApplicationInfo.FLAG_TEST_ONLY, "TEST_ONLY",
        ApplicationInfo.FLAG_VM_SAFE_MODE, "VM_SAFE_MODE",
        ApplicationInfo.FLAG_ALLOW_BACKUP, "ALLOW_BACKUP",
        ApplicationInfo.FLAG_KILL_AFTER_RESTORE, "KILL_AFTER_RESTORE",
        ApplicationInfo.FLAG_RESTORE_ANY_VERSION, "RESTORE_ANY_VERSION",
        ApplicationInfo.FLAG_EXTERNAL_STORAGE, "EXTERNAL_STORAGE",
        ApplicationInfo.FLAG_LARGE_HEAP, "LARGE_HEAP",
        ApplicationInfo.FLAG_FORWARD_LOCK, "FORWARD_LOCK",
        ApplicationInfo.FLAG_CANT_SAVE_STATE, "CANT_SAVE_STATE",
    };

    void dumpPackagesLPr(PrintWriter pw, String packageName, DumpState dumpState) {
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        final Date date = new Date();
        boolean printedSomething = false;
        List<UserInfo> users = getAllUsers();
        for (final PackageSetting ps : mPackages.values()) {
            if (packageName != null && !packageName.equals(ps.realName)
                    && !packageName.equals(ps.name)) {
                continue;
            }

            if (packageName != null) {
                dumpState.setSharedUser(ps.sharedUser);
            }

            if (!printedSomething) {
                if (dumpState.onTitlePrinted())
                    pw.println(" ");
                pw.println("Packages:");
                printedSomething = true;
            }
            pw.print("  Package [");
                pw.print(ps.realName != null ? ps.realName : ps.name);
                pw.print("] (");
                pw.print(Integer.toHexString(System.identityHashCode(ps)));
                pw.println("):");

            if (ps.realName != null) {
                pw.print("    compat name=");
                pw.println(ps.name);
            }

            pw.print("    userId="); pw.print(ps.appId);
            pw.print(" gids="); pw.println(PackageManagerService.arrayToString(ps.gids));
            pw.print("    sharedUser="); pw.println(ps.sharedUser);
            pw.print("    pkg="); pw.println(ps.pkg);
            pw.print("    codePath="); pw.println(ps.codePathString);
            pw.print("    resourcePath="); pw.println(ps.resourcePathString);
            pw.print("    nativeLibraryPath="); pw.println(ps.nativeLibraryPathString);
            pw.print("    versionCode="); pw.println(ps.versionCode);
            if (ps.pkg != null) {
                pw.print("    applicationInfo="); pw.println(ps.pkg.applicationInfo.toString());
                pw.print("    flags="); printFlags(pw, ps.pkg.applicationInfo.flags, FLAG_DUMP_SPEC); pw.println();
                pw.print("    versionName="); pw.println(ps.pkg.mVersionName);
                pw.print("    dataDir="); pw.println(ps.pkg.applicationInfo.dataDir);
                pw.print("    targetSdk="); pw.println(ps.pkg.applicationInfo.targetSdkVersion);
                if (ps.pkg.mOperationPending) {
                    pw.println("    mOperationPending=true");
                }
                pw.print("    supportsScreens=[");
                boolean first = true;
                if ((ps.pkg.applicationInfo.flags & ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS) != 0) {
                    if (!first)
                        pw.print(", ");
                    first = false;
                    pw.print("small");
                }
                if ((ps.pkg.applicationInfo.flags & ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS) != 0) {
                    if (!first)
                        pw.print(", ");
                    first = false;
                    pw.print("medium");
                }
                if ((ps.pkg.applicationInfo.flags & ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS) != 0) {
                    if (!first)
                        pw.print(", ");
                    first = false;
                    pw.print("large");
                }
                if ((ps.pkg.applicationInfo.flags & ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS) != 0) {
                    if (!first)
                        pw.print(", ");
                    first = false;
                    pw.print("xlarge");
                }
                if ((ps.pkg.applicationInfo.flags & ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS) != 0) {
                    if (!first)
                        pw.print(", ");
                    first = false;
                    pw.print("resizeable");
                }
                if ((ps.pkg.applicationInfo.flags & ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES) != 0) {
                    if (!first)
                        pw.print(", ");
                    first = false;
                    pw.print("anyDensity");
                }
                pw.println("]");
            }
            pw.print("    timeStamp=");
                date.setTime(ps.timeStamp);
                pw.println(sdf.format(date));
            pw.print("    firstInstallTime=");
                date.setTime(ps.firstInstallTime);
                pw.println(sdf.format(date));
            pw.print("    lastUpdateTime=");
                date.setTime(ps.lastUpdateTime);
                pw.println(sdf.format(date));
            if (ps.installerPackageName != null) {
                pw.print("    installerPackageName="); pw.println(ps.installerPackageName);
            }
            pw.print("    signatures="); pw.println(ps.signatures);
            pw.print("    permissionsFixed="); pw.print(ps.permissionsFixed);
                    pw.print(" haveGids="); pw.print(ps.haveGids);
                    pw.print(" installStatus="); pw.println(ps.installStatus);
            pw.print("    pkgFlags="); printFlags(pw, ps.pkgFlags, FLAG_DUMP_SPEC);
                    pw.println();
            for (UserInfo user : users) {
                pw.print("    User "); pw.print(user.id); pw.print(": ");
                pw.print(" installed=");
                pw.print(ps.getInstalled(user.id));
                pw.print(" stopped=");
                pw.print(ps.getStopped(user.id));
                pw.print(" notLaunched=");
                pw.print(ps.getNotLaunched(user.id));
                pw.print(" enabled=");
                pw.println(ps.getEnabled(user.id));
                HashSet<String> cmp = ps.getDisabledComponents(user.id);
                if (cmp != null && cmp.size() > 0) {
                    pw.println("      disabledComponents:");
                    for (String s : cmp) {
                        pw.print("      "); pw.println(s);
                    }
                }
                cmp = ps.getEnabledComponents(user.id);
                if (cmp != null && cmp.size() > 0) {
                    pw.println("      enabledComponents:");
                    for (String s : cmp) {
                        pw.print("      "); pw.println(s);
                    }
                }
            }
            if (ps.grantedPermissions.size() > 0) {
                pw.println("    grantedPermissions:");
                for (String s : ps.grantedPermissions) {
                    pw.print("      "); pw.println(s);
                }
            }
        }

        printedSomething = false;
        if (mRenamedPackages.size() > 0) {
            for (final Map.Entry<String, String> e : mRenamedPackages.entrySet()) {
                if (packageName != null && !packageName.equals(e.getKey())
                        && !packageName.equals(e.getValue())) {
                    continue;
                }
                if (!printedSomething) {
                    if (dumpState.onTitlePrinted())
                        pw.println(" ");
                    pw.println("Renamed packages:");
                    printedSomething = true;
                }
                pw.print("  ");
                pw.print(e.getKey());
                pw.print(" -> ");
                pw.println(e.getValue());
            }
        }

        printedSomething = false;
        if (mDisabledSysPackages.size() > 0) {
            for (final PackageSetting ps : mDisabledSysPackages.values()) {
                if (packageName != null && !packageName.equals(ps.realName)
                        && !packageName.equals(ps.name)) {
                    continue;
                }
                if (!printedSomething) {
                    if (dumpState.onTitlePrinted())
                        pw.println(" ");
                    pw.println("Hidden system packages:");
                    printedSomething = true;
                }
                pw.print("  Package [");
                pw.print(ps.realName != null ? ps.realName : ps.name);
                pw.print("] (");
                pw.print(Integer.toHexString(System.identityHashCode(ps)));
                pw.println("):");
                if (ps.realName != null) {
                    pw.print("    compat name=");
                    pw.println(ps.name);
                }
                if (ps.pkg != null && ps.pkg.applicationInfo != null) {
                    pw.print("    applicationInfo=");
                    pw.println(ps.pkg.applicationInfo.toString());
                }
                pw.print("    userId=");
                pw.println(ps.appId);
                pw.print("    sharedUser=");
                pw.println(ps.sharedUser);
                pw.print("    codePath=");
                pw.println(ps.codePathString);
                pw.print("    resourcePath=");
                pw.println(ps.resourcePathString);
            }
        }
    }

    void dumpPermissionsLPr(PrintWriter pw, String packageName, DumpState dumpState) {
        boolean printedSomething = false;
        for (BasePermission p : mPermissions.values()) {
            if (packageName != null && !packageName.equals(p.sourcePackage)) {
                continue;
            }
            if (!printedSomething) {
                if (dumpState.onTitlePrinted())
                    pw.println(" ");
                pw.println("Permissions:");
                printedSomething = true;
            }
            pw.print("  Permission ["); pw.print(p.name); pw.print("] (");
                    pw.print(Integer.toHexString(System.identityHashCode(p)));
                    pw.println("):");
            pw.print("    sourcePackage="); pw.println(p.sourcePackage);
            pw.print("    uid="); pw.print(p.uid);
                    pw.print(" gids="); pw.print(PackageManagerService.arrayToString(p.gids));
                    pw.print(" type="); pw.print(p.type);
                    pw.print(" prot=");
                    pw.println(PermissionInfo.protectionToString(p.protectionLevel));
            if (p.packageSetting != null) {
                pw.print("    packageSetting="); pw.println(p.packageSetting);
            }
            if (p.perm != null) {
                pw.print("    perm="); pw.println(p.perm);
            }
            if (READ_EXTERNAL_STORAGE.equals(p.name)) {
                pw.print("    enforced=");
                pw.println(mReadExternalStorageEnforced);
            }
        }
    }

    void dumpSharedUsersLPr(PrintWriter pw, String packageName, DumpState dumpState) {
        boolean printedSomething = false;
        for (SharedUserSetting su : mSharedUsers.values()) {
            if (packageName != null && su != dumpState.getSharedUser()) {
                continue;
            }
            if (!printedSomething) {
                if (dumpState.onTitlePrinted())
                    pw.println(" ");
                pw.println("Shared users:");
                printedSomething = true;
            }
            pw.print("  SharedUser [");
            pw.print(su.name);
            pw.print("] (");
            pw.print(Integer.toHexString(System.identityHashCode(su)));
                    pw.println("):");
            pw.print("    userId=");
            pw.print(su.userId);
            pw.print(" gids=");
            pw.println(PackageManagerService.arrayToString(su.gids));
            pw.println("    grantedPermissions:");
            for (String s : su.grantedPermissions) {
                pw.print("      ");
                pw.println(s);
            }
        }
    }

    void dumpReadMessagesLPr(PrintWriter pw, DumpState dumpState) {
        pw.println("Settings parse messages:");
        pw.print(mReadMessages.toString());
    }
}
