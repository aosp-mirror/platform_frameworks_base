/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.commands.pm;

import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.PackageInstallObserver;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.UserInfo;
import android.content.pm.VerificationParams;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import libcore.io.IoUtils;

import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.SizedInputStream;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public final class Pm {
    private static final String TAG = "Pm";

    IPackageManager mPm;
    IPackageInstaller mInstaller;
    IUserManager mUm;

    private WeakHashMap<String, Resources> mResourceCache
            = new WeakHashMap<String, Resources>();

    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private static final String PM_NOT_RUNNING_ERR =
        "Error: Could not access the Package Manager.  Is the system running?";

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            exitCode = new Pm().run(args);
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            System.err.println("Error: " + e);
            if (e instanceof RemoteException) {
                System.err.println(PM_NOT_RUNNING_ERR);
            }
        }
        System.exit(exitCode);
    }

    public int run(String[] args) throws IOException, RemoteException {
        boolean validCommand = false;
        if (args.length < 1) {
            return showUsage();
        }

        mUm = IUserManager.Stub.asInterface(ServiceManager.getService("user"));
        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
        mInstaller = mPm.getPackageInstaller();

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if ("list".equals(op)) {
            return runList();
        }

        if ("path".equals(op)) {
            return runPath();
        }

        if ("dump".equals(op)) {
            return runDump();
        }

        if ("install".equals(op)) {
            return runInstall();
        }

        if ("install-create".equals(op)) {
            return runInstallCreate();
        }

        if ("install-write".equals(op)) {
            return runInstallWrite();
        }

        if ("install-commit".equals(op)) {
            return runInstallCommit();
        }

        if ("install-abandon".equals(op) || "install-destroy".equals(op)) {
            return runInstallAbandon();
        }

        if ("set-installer".equals(op)) {
            return runSetInstaller();
        }

        if ("uninstall".equals(op)) {
            return runUninstall();
        }

        if ("clear".equals(op)) {
            return runClear();
        }

        if ("enable".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        }

        if ("disable".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        }

        if ("disable-user".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
        }

        if ("disable-until-used".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);
        }

        if ("hide".equals(op)) {
            return runSetHiddenSetting(true);
        }

        if ("unhide".equals(op)) {
            return runSetHiddenSetting(false);
        }

        if ("grant".equals(op)) {
            return runGrantRevokePermission(true);
        }

        if ("revoke".equals(op)) {
            return runGrantRevokePermission(false);
        }

        if ("reset-permissions".equals(op)) {
            return runResetPermissions();
        }

        if ("set-permission-enforced".equals(op)) {
            return runSetPermissionEnforced();
        }

        if ("set-app-link".equals(op)) {
            return runSetAppLink();
        }

        if ("get-app-link".equals(op)) {
            return runGetAppLink();
        }

        if ("set-install-location".equals(op)) {
            return runSetInstallLocation();
        }

        if ("get-install-location".equals(op)) {
            return runGetInstallLocation();
        }

        if ("trim-caches".equals(op)) {
            return runTrimCaches();
        }

        if ("create-user".equals(op)) {
            return runCreateUser();
        }

        if ("remove-user".equals(op)) {
            return runRemoveUser();
        }

        if ("get-max-users".equals(op)) {
            return runGetMaxUsers();
        }

        if ("force-dex-opt".equals(op)) {
            return runForceDexOpt();
        }

        if ("move-package".equals(op)) {
            return runMovePackage();
        }

        if ("move-primary-storage".equals(op)) {
            return runMovePrimaryStorage();
        }

        try {
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("-l")) {
                    validCommand = true;
                    return runListPackages(false);
                } else if (args[0].equalsIgnoreCase("-lf")){
                    validCommand = true;
                    return runListPackages(true);
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("-p")) {
                    validCommand = true;
                    return displayPackageFilePath(args[1]);
                }
            }
            return 1;
        } finally {
            if (validCommand == false) {
                if (op != null) {
                    System.err.println("Error: unknown command '" + op + "'");
                }
                showUsage();
            }
        }
    }

    /**
     * Execute the list sub-command.
     *
     * pm list [package | packages]
     * pm list permission-groups
     * pm list permissions
     * pm list features
     * pm list libraries
     * pm list instrumentation
     */
    private int runList() {
        String type = nextArg();
        if (type == null) {
            System.err.println("Error: didn't specify type of data to list");
            return 1;
        }
        if ("package".equals(type) || "packages".equals(type)) {
            return runListPackages(false);
        } else if ("permission-groups".equals(type)) {
            return runListPermissionGroups();
        } else if ("permissions".equals(type)) {
            return runListPermissions();
        } else if ("features".equals(type)) {
            return runListFeatures();
        } else if ("libraries".equals(type)) {
            return runListLibraries();
        } else if ("instrumentation".equals(type)) {
            return runListInstrumentation();
        } else if ("users".equals(type)) {
            return runListUsers();
        } else {
            System.err.println("Error: unknown list type '" + type + "'");
            return 1;
        }
    }

    /**
     * Lists all the installed packages.
     */
    private int runListPackages(boolean showApplicationPackage) {
        int getFlags = 0;
        boolean listDisabled = false, listEnabled = false;
        boolean listSystem = false, listThirdParty = false;
        boolean listInstaller = false;
        int userId = UserHandle.USER_OWNER;
        try {
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("-l")) {
                    // old compat
                } else if (opt.equals("-lf")) {
                    showApplicationPackage = true;
                } else if (opt.equals("-f")) {
                    showApplicationPackage = true;
                } else if (opt.equals("-d")) {
                    listDisabled = true;
                } else if (opt.equals("-e")) {
                    listEnabled = true;
                } else if (opt.equals("-s")) {
                    listSystem = true;
                } else if (opt.equals("-3")) {
                    listThirdParty = true;
                } else if (opt.equals("-i")) {
                    listInstaller = true;
                } else if (opt.equals("--user")) {
                    userId = Integer.parseInt(nextArg());
                } else if (opt.equals("-u")) {
                    getFlags |= PackageManager.GET_UNINSTALLED_PACKAGES;
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    return 1;
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Error: " + ex.toString());
            return 1;
        }

        String filter = nextArg();

        try {
            final List<PackageInfo> packages = getInstalledPackages(mPm, getFlags, userId);

            int count = packages.size();
            for (int p = 0 ; p < count ; p++) {
                PackageInfo info = packages.get(p);
                if (filter != null && !info.packageName.contains(filter)) {
                    continue;
                }
                final boolean isSystem =
                        (info.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0;
                if ((!listDisabled || !info.applicationInfo.enabled) &&
                        (!listEnabled || info.applicationInfo.enabled) &&
                        (!listSystem || isSystem) &&
                        (!listThirdParty || !isSystem)) {
                    System.out.print("package:");
                    if (showApplicationPackage) {
                        System.out.print(info.applicationInfo.sourceDir);
                        System.out.print("=");
                    }
                    System.out.print(info.packageName);
                    if (listInstaller) {
                        System.out.print("  installer=");
                        System.out.print(mPm.getInstallerPackageName(info.packageName));
                    }
                    System.out.println();
                }
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    @SuppressWarnings("unchecked")
    private List<PackageInfo> getInstalledPackages(IPackageManager pm, int flags, int userId)
            throws RemoteException {
        ParceledListSlice<PackageInfo> slice = pm.getInstalledPackages(flags, userId);
        return slice.getList();
    }

    /**
     * Lists all of the features supported by the current device.
     *
     * pm list features
     */
    private int runListFeatures() {
        try {
            List<FeatureInfo> list = new ArrayList<FeatureInfo>();
            FeatureInfo[] rawList = mPm.getSystemAvailableFeatures();
            for (int i=0; i<rawList.length; i++) {
                list.add(rawList[i]);
            }


            // Sort by name
            Collections.sort(list, new Comparator<FeatureInfo>() {
                public int compare(FeatureInfo o1, FeatureInfo o2) {
                    if (o1.name == o2.name) return 0;
                    if (o1.name == null) return -1;
                    if (o2.name == null) return 1;
                    return o1.name.compareTo(o2.name);
                }
            });

            int count = (list != null) ? list.size() : 0;
            for (int p = 0; p < count; p++) {
                FeatureInfo fi = list.get(p);
                System.out.print("feature:");
                if (fi.name != null) System.out.println(fi.name);
                else System.out.println("reqGlEsVersion=0x"
                        + Integer.toHexString(fi.reqGlEsVersion));
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    /**
     * Lists all of the libraries supported by the current device.
     *
     * pm list libraries
     */
    private int runListLibraries() {
        try {
            List<String> list = new ArrayList<String>();
            String[] rawList = mPm.getSystemSharedLibraryNames();
            for (int i=0; i<rawList.length; i++) {
                list.add(rawList[i]);
            }


            // Sort by name
            Collections.sort(list, new Comparator<String>() {
                public int compare(String o1, String o2) {
                    if (o1 == o2) return 0;
                    if (o1 == null) return -1;
                    if (o2 == null) return 1;
                    return o1.compareTo(o2);
                }
            });

            int count = (list != null) ? list.size() : 0;
            for (int p = 0; p < count; p++) {
                String lib = list.get(p);
                System.out.print("library:");
                System.out.println(lib);
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    /**
     * Lists all of the installed instrumentation, or all for a given package
     *
     * pm list instrumentation [package] [-f]
     */
    private int runListInstrumentation() {
        int flags = 0;      // flags != 0 is only used to request meta-data
        boolean showPackage = false;
        String targetPackage = null;

        try {
            String opt;
            while ((opt=nextArg()) != null) {
                if (opt.equals("-f")) {
                    showPackage = true;
                } else if (opt.charAt(0) != '-') {
                    targetPackage = opt;
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    return 1;
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Error: " + ex.toString());
            return 1;
        }

        try {
            List<InstrumentationInfo> list = mPm.queryInstrumentation(targetPackage, flags);

            // Sort by target package
            Collections.sort(list, new Comparator<InstrumentationInfo>() {
                public int compare(InstrumentationInfo o1, InstrumentationInfo o2) {
                    return o1.targetPackage.compareTo(o2.targetPackage);
                }
            });

            int count = (list != null) ? list.size() : 0;
            for (int p = 0; p < count; p++) {
                InstrumentationInfo ii = list.get(p);
                System.out.print("instrumentation:");
                if (showPackage) {
                    System.out.print(ii.sourceDir);
                    System.out.print("=");
                }
                ComponentName cn = new ComponentName(ii.packageName, ii.name);
                System.out.print(cn.flattenToShortString());
                System.out.print(" (target=");
                System.out.print(ii.targetPackage);
                System.out.println(")");
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    /**
     * Lists all the known permission groups.
     */
    private int runListPermissionGroups() {
        try {
            List<PermissionGroupInfo> pgs = mPm.getAllPermissionGroups(0);

            int count = pgs.size();
            for (int p = 0 ; p < count ; p++) {
                PermissionGroupInfo pgi = pgs.get(p);
                System.out.print("permission group:");
                System.out.println(pgi.name);
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private String loadText(PackageItemInfo pii, int res, CharSequence nonLocalized) {
        if (nonLocalized != null) {
            return nonLocalized.toString();
        }
        if (res != 0) {
            Resources r = getResources(pii);
            if (r != null) {
                try {
                    return r.getString(res);
                } catch (Resources.NotFoundException e) {
                }
            }
        }
        return null;
    }

    /**
     * Lists all the permissions in a group.
     */
    private int runListPermissions() {
        try {
            boolean labels = false;
            boolean groups = false;
            boolean userOnly = false;
            boolean summary = false;
            boolean dangerousOnly = false;
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("-f")) {
                    labels = true;
                } else if (opt.equals("-g")) {
                    groups = true;
                } else if (opt.equals("-s")) {
                    groups = true;
                    labels = true;
                    summary = true;
                } else if (opt.equals("-u")) {
                    userOnly = true;
                } else if (opt.equals("-d")) {
                    dangerousOnly = true;
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    return 1;
                }
            }

            String grp = nextOption();
            ArrayList<String> groupList = new ArrayList<String>();
            if (groups) {
                List<PermissionGroupInfo> infos =
                        mPm.getAllPermissionGroups(0);
                for (int i=0; i<infos.size(); i++) {
                    groupList.add(infos.get(i).name);
                }
                groupList.add(null);
            } else {
                groupList.add(grp);
            }

            if (dangerousOnly) {
                System.out.println("Dangerous Permissions:");
                System.out.println("");
                doListPermissions(groupList, groups, labels, summary,
                        PermissionInfo.PROTECTION_DANGEROUS,
                        PermissionInfo.PROTECTION_DANGEROUS);
                if (userOnly) {
                    System.out.println("Normal Permissions:");
                    System.out.println("");
                    doListPermissions(groupList, groups, labels, summary,
                            PermissionInfo.PROTECTION_NORMAL,
                            PermissionInfo.PROTECTION_NORMAL);
                }
            } else if (userOnly) {
                System.out.println("Dangerous and Normal Permissions:");
                System.out.println("");
                doListPermissions(groupList, groups, labels, summary,
                        PermissionInfo.PROTECTION_NORMAL,
                        PermissionInfo.PROTECTION_DANGEROUS);
            } else {
                System.out.println("All Permissions:");
                System.out.println("");
                doListPermissions(groupList, groups, labels, summary,
                        -10000, 10000);
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private void doListPermissions(ArrayList<String> groupList,
            boolean groups, boolean labels, boolean summary,
            int startProtectionLevel, int endProtectionLevel)
            throws RemoteException {
        for (int i=0; i<groupList.size(); i++) {
            String groupName = groupList.get(i);
            String prefix = "";
            if (groups) {
                if (i > 0) System.out.println("");
                if (groupName != null) {
                    PermissionGroupInfo pgi = mPm.getPermissionGroupInfo(
                            groupName, 0);
                    if (summary) {
                        Resources res = getResources(pgi);
                        if (res != null) {
                            System.out.print(loadText(pgi, pgi.labelRes,
                                    pgi.nonLocalizedLabel) + ": ");
                        } else {
                            System.out.print(pgi.name + ": ");

                        }
                    } else {
                        System.out.println((labels ? "+ " : "")
                                + "group:" + pgi.name);
                        if (labels) {
                            System.out.println("  package:" + pgi.packageName);
                            Resources res = getResources(pgi);
                            if (res != null) {
                                System.out.println("  label:"
                                        + loadText(pgi, pgi.labelRes,
                                                pgi.nonLocalizedLabel));
                                System.out.println("  description:"
                                        + loadText(pgi, pgi.descriptionRes,
                                                pgi.nonLocalizedDescription));
                            }
                        }
                    }
                } else {
                    System.out.println(((labels && !summary)
                            ? "+ " : "") + "ungrouped:");
                }
                prefix = "  ";
            }
            List<PermissionInfo> ps = mPm.queryPermissionsByGroup(
                    groupList.get(i), 0);
            int count = ps.size();
            boolean first = true;
            for (int p = 0 ; p < count ; p++) {
                PermissionInfo pi = ps.get(p);
                if (groups && groupName == null && pi.group != null) {
                    continue;
                }
                final int base = pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                if (base < startProtectionLevel
                        || base > endProtectionLevel) {
                    continue;
                }
                if (summary) {
                    if (first) {
                        first = false;
                    } else {
                        System.out.print(", ");
                    }
                    Resources res = getResources(pi);
                    if (res != null) {
                        System.out.print(loadText(pi, pi.labelRes,
                                pi.nonLocalizedLabel));
                    } else {
                        System.out.print(pi.name);
                    }
                } else {
                    System.out.println(prefix + (labels ? "+ " : "")
                            + "permission:" + pi.name);
                    if (labels) {
                        System.out.println(prefix + "  package:" + pi.packageName);
                        Resources res = getResources(pi);
                        if (res != null) {
                            System.out.println(prefix + "  label:"
                                    + loadText(pi, pi.labelRes,
                                            pi.nonLocalizedLabel));
                            System.out.println(prefix + "  description:"
                                    + loadText(pi, pi.descriptionRes,
                                            pi.nonLocalizedDescription));
                        }
                        System.out.println(prefix + "  protectionLevel:"
                                + PermissionInfo.protectionToString(pi.protectionLevel));
                    }
                }
            }

            if (summary) {
                System.out.println("");
            }
        }
    }

    private int runPath() {
        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            return 1;
        }
        return displayPackageFilePath(pkg);
    }

    private int runDump() {
        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            return 1;
        }
        ActivityManager.dumpPackageStateStatic(FileDescriptor.out, pkg);
        return 0;
    }

    class LocalPackageInstallObserver extends PackageInstallObserver {
        boolean finished;
        int result;
        String extraPermission;
        String extraPackage;

        @Override
        public void onPackageInstalled(String name, int status, String msg, Bundle extras) {
            synchronized (this) {
                finished = true;
                result = status;
                if (status == PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION) {
                    extraPermission = extras.getString(
                            PackageManager.EXTRA_FAILURE_EXISTING_PERMISSION);
                    extraPackage = extras.getString(
                            PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE);
                }
                notifyAll();
            }
        }
    }

    /**
     * Converts a failure code into a string by using reflection to find a matching constant
     * in PackageManager.
     */
    private String installFailureToString(LocalPackageInstallObserver obs) {
        final int result = obs.result;
        Field[] fields = PackageManager.class.getFields();
        for (Field f: fields) {
            if (f.getType() == int.class) {
                int modifiers = f.getModifiers();
                // only look at public final static fields.
                if (((modifiers & Modifier.FINAL) != 0) &&
                        ((modifiers & Modifier.PUBLIC) != 0) &&
                        ((modifiers & Modifier.STATIC) != 0)) {
                    String fieldName = f.getName();
                    if (fieldName.startsWith("INSTALL_FAILED_") ||
                            fieldName.startsWith("INSTALL_PARSE_FAILED_")) {
                        // get the int value and compare it to result.
                        try {
                            if (result == f.getInt(null)) {
                                StringBuilder sb = new StringBuilder(64);
                                sb.append(fieldName);
                                if (obs.extraPermission != null) {
                                    sb.append(" perm=");
                                    sb.append(obs.extraPermission);
                                }
                                if (obs.extraPackage != null) {
                                    sb.append(" pkg=" + obs.extraPackage);
                                }
                                return sb.toString();
                            }
                        } catch (IllegalAccessException e) {
                            // this shouldn't happen since we only look for public static fields.
                        }
                    }
                }
            }
        }

        // couldn't find a matching constant? return the value
        return Integer.toString(result);
    }

    // pm set-app-link [--user USER_ID] PACKAGE {always|ask|never|undefined}
    private int runSetAppLink() {
        int userId = UserHandle.USER_OWNER;

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = Integer.parseInt(nextOptionData());
                if (userId < 0) {
                    System.err.println("Error: user must be >= 0");
                    return 1;
                }
            } else {
                System.err.println("Error: unknown option: " + opt);
                showUsage();
                return 1;
            }
        }

        // Package name to act on; required
        final String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified.");
            showUsage();
            return 1;
        }

        // State to apply; {always|ask|never|undefined}, required
        final String modeString = nextArg();
        if (modeString == null) {
            System.err.println("Error: no app link state specified.");
            showUsage();
            return 1;
        }

        final int newMode;
        switch (modeString.toLowerCase()) {
            case "undefined":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
                break;

            case "always":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
                break;

            case "ask":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
                break;

            case "never":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;
                break;

            default:
                System.err.println("Error: unknown app link state '" + modeString + "'");
                return 1;
        }

        try {
            final PackageInfo info = mPm.getPackageInfo(pkg, 0, userId);
            if (info == null) {
                System.err.println("Error: package " + pkg + " not found.");
                return 1;
            }

            if ((info.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS) == 0) {
                System.err.println("Error: package " + pkg + " does not handle web links.");
                return 1;
            }

            if (!mPm.updateIntentVerificationStatus(pkg, newMode, userId)) {
                System.err.println("Error: unable to update app link status for " + pkg);
                return 1;
            }
        } catch (Exception e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }

        return 0;
    }

    // pm get-app-link [--user USER_ID] PACKAGE
    private int runGetAppLink() {
        int userId = UserHandle.USER_OWNER;

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = Integer.parseInt(nextOptionData());
                if (userId < 0) {
                    System.err.println("Error: user must be >= 0");
                    return 1;
                }
            } else {
                System.err.println("Error: unknown option: " + opt);
                showUsage();
                return 1;
            }
        }

        // Package name to act on; required
        final String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified.");
            showUsage();
            return 1;
        }

        try {
            final PackageInfo info = mPm.getPackageInfo(pkg, 0, userId);
            if (info == null) {
                System.err.println("Error: package " + pkg + " not found.");
                return 1;
            }

            if ((info.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS) == 0) {
                System.err.println("Error: package " + pkg + " does not handle web links.");
                return 1;
            }

            System.out.println(linkStateToString(mPm.getIntentVerificationStatus(pkg, userId)));
        } catch (Exception e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }

        return 0;
    }

    private String linkStateToString(int state) {
        switch (state) {
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED: return "undefined";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK: return "ask";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS: return "always";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER: return "never";
        }
        return "Unknown link state: " + state;
    }

    private int runSetInstallLocation() {
        int loc;

        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no install location specified.");
            return 1;
        }
        try {
            loc = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println("Error: install location has to be a number.");
            return 1;
        }
        try {
            if (!mPm.setInstallLocation(loc)) {
                System.err.println("Error: install location has to be a number.");
                return 1;
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runGetInstallLocation() {
        try {
            int loc = mPm.getInstallLocation();
            String locStr = "invalid";
            if (loc == PackageHelper.APP_INSTALL_AUTO) {
                locStr = "auto";
            } else if (loc == PackageHelper.APP_INSTALL_INTERNAL) {
                locStr = "internal";
            } else if (loc == PackageHelper.APP_INSTALL_EXTERNAL) {
                locStr = "external";
            }
            System.out.println(loc + "[" + locStr + "]");
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runInstall() {
        int installFlags = 0;
        int userId = UserHandle.USER_ALL;
        String installerPackageName = null;

        String opt;

        String originatingUriString = null;
        String referrer = null;
        String abi = null;

        while ((opt=nextOption()) != null) {
            if (opt.equals("-l")) {
                installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
            } else if (opt.equals("-r")) {
                installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
            } else if (opt.equals("-i")) {
                installerPackageName = nextOptionData();
                if (installerPackageName == null) {
                    System.err.println("Error: no value specified for -i");
                    return 1;
                }
            } else if (opt.equals("-t")) {
                installFlags |= PackageManager.INSTALL_ALLOW_TEST;
            } else if (opt.equals("-s")) {
                // Override if -s option is specified.
                installFlags |= PackageManager.INSTALL_EXTERNAL;
            } else if (opt.equals("-f")) {
                // Override if -s option is specified.
                installFlags |= PackageManager.INSTALL_INTERNAL;
            } else if (opt.equals("-d")) {
                installFlags |= PackageManager.INSTALL_ALLOW_DOWNGRADE;
            } else if (opt.equals("-g")) {
                installFlags |= PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS;
            } else if (opt.equals("--originating-uri")) {
                originatingUriString = nextOptionData();
                if (originatingUriString == null) {
                    System.err.println("Error: must supply argument for --originating-uri");
                    return 1;
                }
            } else if (opt.equals("--referrer")) {
                referrer = nextOptionData();
                if (referrer == null) {
                    System.err.println("Error: must supply argument for --referrer");
                    return 1;
                }
            } else if (opt.equals("--abi")) {
                abi = checkAbiArgument(nextOptionData());
            } else if (opt.equals("--user")) {
                userId = Integer.parseInt(nextOptionData());
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return 1;
            }
        }

        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_OWNER;
            installFlags |= PackageManager.INSTALL_ALL_USERS;
        }

        final Uri verificationURI;
        final Uri originatingURI;
        final Uri referrerURI;

        if (originatingUriString != null) {
            originatingURI = Uri.parse(originatingUriString);
        } else {
            originatingURI = null;
        }

        if (referrer != null) {
            referrerURI = Uri.parse(referrer);
        } else {
            referrerURI = null;
        }

        // Populate apkURI, must be present
        final String apkFilePath = nextArg();
        System.err.println("\tpkg: " + apkFilePath);
        if (apkFilePath == null) {
            System.err.println("Error: no package specified");
            return 1;
        }

        // Populate verificationURI, optionally present
        final String verificationFilePath = nextArg();
        if (verificationFilePath != null) {
            System.err.println("\tver: " + verificationFilePath);
            verificationURI = Uri.fromFile(new File(verificationFilePath));
        } else {
            verificationURI = null;
        }

        LocalPackageInstallObserver obs = new LocalPackageInstallObserver();
        try {
            VerificationParams verificationParams = new VerificationParams(verificationURI,
                    originatingURI, referrerURI, VerificationParams.NO_UID, null);

            mPm.installPackageAsUser(apkFilePath, obs.getBinder(), installFlags,
                    installerPackageName, verificationParams, abi, userId);

            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
                if (obs.result == PackageManager.INSTALL_SUCCEEDED) {
                    System.out.println("Success");
                    return 0;
                } else {
                    System.err.println("Failure ["
                            + installFailureToString(obs)
                            + "]");
                    return 1;
                }
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runInstallCreate() throws RemoteException {
        int userId = UserHandle.USER_ALL;
        String installerPackageName = null;

        final SessionParams params = new SessionParams(SessionParams.MODE_FULL_INSTALL);

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("-l")) {
                params.installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
            } else if (opt.equals("-r")) {
                params.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
            } else if (opt.equals("-i")) {
                installerPackageName = nextArg();
                if (installerPackageName == null) {
                    throw new IllegalArgumentException("Missing installer package");
                }
            } else if (opt.equals("-t")) {
                params.installFlags |= PackageManager.INSTALL_ALLOW_TEST;
            } else if (opt.equals("-s")) {
                params.installFlags |= PackageManager.INSTALL_EXTERNAL;
            } else if (opt.equals("-f")) {
                params.installFlags |= PackageManager.INSTALL_INTERNAL;
            } else if (opt.equals("-d")) {
                params.installFlags |= PackageManager.INSTALL_ALLOW_DOWNGRADE;
            } else if (opt.equals("-g")) {
                params.installFlags |= PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS;
            } else if (opt.equals("--originating-uri")) {
                params.originatingUri = Uri.parse(nextOptionData());
            } else if (opt.equals("--referrer")) {
                params.referrerUri = Uri.parse(nextOptionData());
            } else if (opt.equals("-p")) {
                params.mode = SessionParams.MODE_INHERIT_EXISTING;
                params.appPackageName = nextOptionData();
                if (params.appPackageName == null) {
                    throw new IllegalArgumentException("Missing inherit package name");
                }
            } else if (opt.equals("-S")) {
                params.setSize(Long.parseLong(nextOptionData()));
            } else if (opt.equals("--abi")) {
                params.abiOverride = checkAbiArgument(nextOptionData());
            } else if (opt.equals("--user")) {
                userId = Integer.parseInt(nextOptionData());
            } else if (opt.equals("--install-location")) {
                params.installLocation = Integer.parseInt(nextOptionData());
            } else if (opt.equals("--force-uuid")) {
                params.installFlags |= PackageManager.INSTALL_FORCE_VOLUME_UUID;
                params.volumeUuid = nextOptionData();
                if ("internal".equals(params.volumeUuid)) {
                    params.volumeUuid = null;
                }
            } else {
                throw new IllegalArgumentException("Unknown option " + opt);
            }
        }

        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_OWNER;
            params.installFlags |= PackageManager.INSTALL_ALL_USERS;
        }

        final int sessionId = mInstaller.createSession(params, installerPackageName, userId);

        // NOTE: adb depends on parsing this string
        System.out.println("Success: created install session [" + sessionId + "]");
        return 0;
    }

    private int runInstallWrite() throws IOException, RemoteException {
        long sizeBytes = -1;

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("-S")) {
                sizeBytes = Long.parseLong(nextOptionData());
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }

        final int sessionId = Integer.parseInt(nextArg());
        final String splitName = nextArg();

        String path = nextArg();
        if ("-".equals(path)) {
            path = null;
        } else if (path != null) {
            final File file = new File(path);
            if (file.isFile()) {
                sizeBytes = file.length();
            }
        }

        final SessionInfo info = mInstaller.getSessionInfo(sessionId);

        PackageInstaller.Session session = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            session = new PackageInstaller.Session(mInstaller.openSession(sessionId));

            if (path != null) {
                in = new FileInputStream(path);
            } else {
                in = new SizedInputStream(System.in, sizeBytes);
            }
            out = session.openWrite(splitName, 0, sizeBytes);

            int total = 0;
            byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                total += c;
                out.write(buffer, 0, c);

                if (info.sizeBytes > 0) {
                    final float fraction = ((float) c / (float) info.sizeBytes);
                    session.addProgress(fraction);
                }
            }
            session.fsync(out);

            System.out.println("Success: streamed " + total + " bytes");
            return 0;
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(session);
        }
    }

    private int runInstallCommit() throws RemoteException {
        final int sessionId = Integer.parseInt(nextArg());

        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(mInstaller.openSession(sessionId));

            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());

            final Intent result = receiver.getResult();
            final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                System.out.println("Success");
                return 0;
            } else {
                Log.e(TAG, "Failure details: " + result.getExtras());
                System.err.println("Failure ["
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
                return 1;
            }
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int runInstallAbandon() throws RemoteException {
        final int sessionId = Integer.parseInt(nextArg());

        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(mInstaller.openSession(sessionId));
            session.abandon();
            System.out.println("Success");
            return 0;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int runSetInstaller() throws RemoteException {
        final String targetPackage = nextArg();
        final String installerPackageName = nextArg();

        if (targetPackage == null || installerPackageName == null) {
            throw new IllegalArgumentException(
                    "must provide both target and installer package names");
        }

        mPm.setInstallerPackageName(targetPackage, installerPackageName);
        System.out.println("Success");
        return 0;
    }

    public int runCreateUser() {
        String name;
        int userId = -1;
        int flags = 0;
        String opt;
        while ((opt = nextOption()) != null) {
            if ("--profileOf".equals(opt)) {
                String optionData = nextOptionData();
                if (optionData == null || !isNumber(optionData)) {
                    System.err.println("Error: no USER_ID specified");
                    showUsage();
                    return 1;
                } else {
                    userId = Integer.parseInt(optionData);
                }
            } else if ("--managed".equals(opt)) {
                flags |= UserInfo.FLAG_MANAGED_PROFILE;
            } else {
                System.err.println("Error: unknown option " + opt);
                showUsage();
                return 1;
            }
        }
        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no user name specified.");
            return 1;
        }
        name = arg;
        try {
            UserInfo info = null;
            if (userId < 0) {
                info = mUm.createUser(name, flags);
            } else {
                info = mUm.createProfileForUser(name, flags, userId);
            }
            if (info != null) {
                System.out.println("Success: created user id " + info.id);
                return 1;
            } else {
                System.err.println("Error: couldn't create User.");
                return 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    public int runRemoveUser() {
        int userId;
        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no user id specified.");
            return 1;
        }
        try {
            userId = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println("Error: user id '" + arg + "' is not a number.");
            return 1;
        }
        try {
            if (mUm.removeUser(userId)) {
                System.out.println("Success: removed user");
                return 0;
            } else {
                System.err.println("Error: couldn't remove user id " + userId);
                return 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    public int runListUsers() {
        try {
            IActivityManager am = ActivityManagerNative.getDefault();

            List<UserInfo> users = mUm.getUsers(false);
            if (users == null) {
                System.err.println("Error: couldn't get users");
                return 1;
            } else {
                System.out.println("Users:");
                for (int i = 0; i < users.size(); i++) {
                    String running = am.isUserRunning(users.get(i).id, false) ? " running" : "";
                    System.out.println("\t" + users.get(i).toString() + running);
                }
                return 0;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    public int runGetMaxUsers() {
        System.out.println("Maximum supported users: " + UserManager.getMaxSupportedUsers());
        return 0;
    }

    public int runForceDexOpt() {
        final String packageName = nextArg();
        try {
            mPm.forceDexOpt(packageName);
            return 0;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public int runMovePackage() {
        final String packageName = nextArg();
        String volumeUuid = nextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }

        try {
            final int moveId = mPm.movePackage(packageName, volumeUuid);

            int status = mPm.getMoveStatus(moveId);
            while (!PackageManager.isMoveStatusFinished(status)) {
                SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);
                status = mPm.getMoveStatus(moveId);
            }

            if (status == PackageManager.MOVE_SUCCEEDED) {
                System.out.println("Success");
                return 0;
            } else {
                System.err.println("Failure [" + status + "]");
                return 1;
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public int runMovePrimaryStorage() {
        String volumeUuid = nextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }

        try {
            final int moveId = mPm.movePrimaryStorage(volumeUuid);

            int status = mPm.getMoveStatus(moveId);
            while (!PackageManager.isMoveStatusFinished(status)) {
                SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);
                status = mPm.getMoveStatus(moveId);
            }

            if (status == PackageManager.MOVE_SUCCEEDED) {
                System.out.println("Success");
                return 0;
            } else {
                System.err.println("Failure [" + status + "]");
                return 1;
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    private int runUninstall() throws RemoteException {
        int flags = 0;
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-k")) {
                flags |= PackageManager.DELETE_KEEP_DATA;
            } else if (opt.equals("--user")) {
                String param = nextArg();
                if (isNumber(param)) {
                    userId = Integer.parseInt(param);
                } else {
                    showUsage();
                    System.err.println("Error: Invalid user: " + param);
                    return 1;
                }
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return 1;
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return 1;
        }

        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_OWNER;
            flags |= PackageManager.DELETE_ALL_USERS;
        } else {
            PackageInfo info;
            try {
                info = mPm.getPackageInfo(pkg, 0, userId);
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
                return 1;
            }
            if (info == null) {
                System.err.println("Failure - not installed for " + userId);
                return 1;
            }
            final boolean isSystem =
                    (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            // If we are being asked to delete a system app for just one
            // user set flag so it disables rather than reverting to system
            // version of the app.
            if (isSystem) {
                flags |= PackageManager.DELETE_SYSTEM_APP;
            }
        }

        final LocalIntentReceiver receiver = new LocalIntentReceiver();
        mInstaller.uninstall(pkg, null /* callerPackageName */, flags,
                receiver.getIntentSender(), userId);

        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            System.out.println("Success");
            return 0;
        } else {
            Log.e(TAG, "Failure details: " + result.getExtras());
            System.err.println("Failure ["
                    + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            return 1;
        }
    }

    static class ClearDataObserver extends IPackageDataObserver.Stub {
        boolean finished;
        boolean result;

        @Override
        public void onRemoveCompleted(String packageName, boolean succeeded) throws RemoteException {
            synchronized (this) {
                finished = true;
                result = succeeded;
                notifyAll();
            }
        }
    }

    private int runClear() {
        int userId = 0;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                showUsage();
                return 1;
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return 1;
        }

        ClearDataObserver obs = new ClearDataObserver();
        try {
            ActivityManagerNative.getDefault().clearApplicationUserData(pkg, obs, userId);
            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (obs.result) {
                System.out.println("Success");
                return 0;
            } else {
                System.err.println("Failed");
                return 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private static String enabledSettingToString(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return "default";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "enabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "disabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return "disabled-user";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "disabled-until-used";
        }
        return "unknown";
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    private int runSetEnabledSetting(int state) {
        int userId = 0;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                showUsage();
                return 1;
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package or component specified");
            showUsage();
            return 1;
        }
        ComponentName cn = ComponentName.unflattenFromString(pkg);
        if (cn == null) {
            try {
                mPm.setApplicationEnabledSetting(pkg, state, 0, userId,
                        "shell:" + android.os.Process.myUid());
                System.out.println("Package " + pkg + " new state: "
                        + enabledSettingToString(
                        mPm.getApplicationEnabledSetting(pkg, userId)));
                return 0;
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
                return 1;
            }
        } else {
            try {
                mPm.setComponentEnabledSetting(cn, state, 0, userId);
                System.out.println("Component " + cn.toShortString() + " new state: "
                        + enabledSettingToString(
                        mPm.getComponentEnabledSetting(cn, userId)));
                return 0;
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
                return 1;
            }
        }
    }

    private int runSetHiddenSetting(boolean state) {
        int userId = 0;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                showUsage();
                return 1;
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package or component specified");
            showUsage();
            return 1;
        }
        try {
            mPm.setApplicationHiddenSettingAsUser(pkg, state, userId);
            System.out.println("Package " + pkg + " new hidden state: "
                    + mPm.getApplicationHiddenSettingAsUser(pkg, userId));
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runGrantRevokePermission(boolean grant) {
        int userId = UserHandle.USER_OWNER;

        String opt = null;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = Integer.parseInt(nextArg());
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return 1;
        }
        String perm = nextArg();
        if (perm == null) {
            System.err.println("Error: no permission specified");
            showUsage();
            return 1;
        }

        try {
            if (grant) {
                mPm.grantRuntimePermission(pkg, perm, userId);
            } else {
                mPm.revokeRuntimePermission(pkg, perm, userId);
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            showUsage();
            return 1;
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
    }

    private int runResetPermissions() {
        try {
            mPm.resetRuntimePermissions();
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            showUsage();
            return 1;
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
    }

    private int runSetPermissionEnforced() {
        final String permission = nextArg();
        if (permission == null) {
            System.err.println("Error: no permission specified");
            showUsage();
            return 1;
        }
        final String enforcedRaw = nextArg();
        if (enforcedRaw == null) {
            System.err.println("Error: no enforcement specified");
            showUsage();
            return 1;
        }
        final boolean enforced = Boolean.parseBoolean(enforcedRaw);
        try {
            mPm.setPermissionEnforced(permission, enforced);
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            showUsage();
            return 1;
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
    }

    static class ClearCacheObserver extends IPackageDataObserver.Stub {
        boolean finished;
        boolean result;

        @Override
        public void onRemoveCompleted(String packageName, boolean succeeded) throws RemoteException {
            synchronized (this) {
                finished = true;
                result = succeeded;
                notifyAll();
            }
        }

    }

    private int runTrimCaches() {
        String size = nextArg();
        if (size == null) {
            System.err.println("Error: no size specified");
            showUsage();
            return 1;
        }
        int len = size.length();
        long multiplier = 1;
        if (len > 1) {
            char c = size.charAt(len-1);
            if (c == 'K' || c == 'k') {
                multiplier = 1024L;
            } else if (c == 'M' || c == 'm') {
                multiplier = 1024L*1024L;
            } else if (c == 'G' || c == 'g') {
                multiplier = 1024L*1024L*1024L;
            } else {
                System.err.println("Invalid suffix: " + c);
                showUsage();
                return 1;
            }
            size = size.substring(0, len-1);
        }
        long sizeVal;
        try {
            sizeVal = Long.parseLong(size) * multiplier;
        } catch (NumberFormatException e) {
            System.err.println("Error: expected number at: " + size);
            showUsage();
            return 1;
        }
        String volumeUuid = nextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }
        ClearDataObserver obs = new ClearDataObserver();
        try {
            mPm.freeStorageAndNotify(volumeUuid, sizeVal, obs);
            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            showUsage();
            return 1;
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
    }

    /**
     * Displays the package file for a package.
     * @param pckg
     */
    private int displayPackageFilePath(String pckg) {
        try {
            PackageInfo info = mPm.getPackageInfo(pckg, 0, 0);
            if (info != null && info.applicationInfo != null) {
                System.out.print("package:");
                System.out.println(info.applicationInfo.sourceDir);
                if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                    for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                        System.out.print("package:");
                        System.out.println(splitSourceDir);
                    }
                }
                return 0;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
        return 1;
    }

    private Resources getResources(PackageItemInfo pii) {
        Resources res = mResourceCache.get(pii.packageName);
        if (res != null) return res;

        try {
            ApplicationInfo ai = mPm.getApplicationInfo(pii.packageName, 0, 0);
            AssetManager am = new AssetManager();
            am.addAssetPath(ai.publicSourceDir);
            res = new Resources(am, null, null);
            mResourceCache.put(pii.packageName, res);
            return res;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return null;
        }
    }

    private static String checkAbiArgument(String abi) {
        if (TextUtils.isEmpty(abi)) {
            throw new IllegalArgumentException("Missing ABI argument");
        }

        if ("-".equals(abi)) {
            return abi;
        }

        final String[] supportedAbis = Build.SUPPORTED_ABIS;
        for (String supportedAbi : supportedAbis) {
            if (supportedAbi.equals(abi)) {
                return abi;
            }
        }

        throw new IllegalArgumentException("ABI " + abi + " not supported on this device");
    }

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public int send(int code, Intent intent, String resolvedType,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return 0;
            }
        };

        public IntentSender getIntentSender() {
            return new IntentSender((IIntentSender) mLocalSender);
        }

        public Intent getResult() {
            try {
                return mResult.take();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String nextOption() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        mNextArg++;
        return data;
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    private static int showUsage() {
        System.err.println("usage: pm list packages [-f] [-d] [-e] [-s] [-3] [-i] [-u] [--user USER_ID] [FILTER]");
        System.err.println("       pm list permission-groups");
        System.err.println("       pm list permissions [-g] [-f] [-d] [-u] [GROUP]");
        System.err.println("       pm list instrumentation [-f] [TARGET-PACKAGE]");
        System.err.println("       pm list features");
        System.err.println("       pm list libraries");
        System.err.println("       pm list users");
        System.err.println("       pm path PACKAGE");
        System.err.println("       pm dump PACKAGE");
        System.err.println("       pm install [-lrtsfd] [-i PACKAGE] [--user USER_ID] [PATH]");
        System.err.println("       pm install-create [-lrtsfdp] [-i PACKAGE] [-S BYTES]");
        System.err.println("               [--install-location 0/1/2]");
        System.err.println("               [--force-uuid internal|UUID]");
        System.err.println("       pm install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH]");
        System.err.println("       pm install-commit SESSION_ID");
        System.err.println("       pm install-abandon SESSION_ID");
        System.err.println("       pm uninstall [-k] [--user USER_ID] PACKAGE");
        System.err.println("       pm set-installer PACKAGE INSTALLER");
        System.err.println("       pm move-package PACKAGE [internal|UUID]");
        System.err.println("       pm move-primary-storage [internal|UUID]");
        System.err.println("       pm clear [--user USER_ID] PACKAGE");
        System.err.println("       pm enable [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-user [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-until-used [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm hide [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm unhide [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm grant [--user USER_ID] PACKAGE PERMISSION");
        System.err.println("       pm revoke [--user USER_ID] PACKAGE PERMISSION");
        System.err.println("       pm reset-permissions");
        System.err.println("       pm set-app-link [--user USER_ID] PACKAGE {always|ask|never|undefined}");
        System.err.println("       pm get-app-link [--user USER_ID] PACKAGE");
        System.err.println("       pm set-install-location [0/auto] [1/internal] [2/external]");
        System.err.println("       pm get-install-location");
        System.err.println("       pm set-permission-enforced PERMISSION [true|false]");
        System.err.println("       pm trim-caches DESIRED_FREE_SPACE [internal|UUID]");
        System.err.println("       pm create-user [--profileOf USER_ID] [--managed] USER_NAME");
        System.err.println("       pm remove-user USER_ID");
        System.err.println("       pm get-max-users");
        System.err.println("");
        System.err.println("pm list packages: prints all packages, optionally only");
        System.err.println("  those whose package name contains the text in FILTER.  Options:");
        System.err.println("    -f: see their associated file.");
        System.err.println("    -d: filter to only show disbled packages.");
        System.err.println("    -e: filter to only show enabled packages.");
        System.err.println("    -s: filter to only show system packages.");
        System.err.println("    -3: filter to only show third party packages.");
        System.err.println("    -i: see the installer for the packages.");
        System.err.println("    -u: also include uninstalled packages.");
        System.err.println("");
        System.err.println("pm list permission-groups: prints all known permission groups.");
        System.err.println("");
        System.err.println("pm list permissions: prints all known permissions, optionally only");
        System.err.println("  those in GROUP.  Options:");
        System.err.println("    -g: organize by group.");
        System.err.println("    -f: print all information.");
        System.err.println("    -s: short summary.");
        System.err.println("    -d: only list dangerous permissions.");
        System.err.println("    -u: list only the permissions users will see.");
        System.err.println("");
        System.err.println("pm list instrumentation: use to list all test packages; optionally");
        System.err.println("  supply <TARGET-PACKAGE> to list the test packages for a particular");
        System.err.println("  application.  Options:");
        System.err.println("    -f: list the .apk file for the test package.");
        System.err.println("");
        System.err.println("pm list features: prints all features of the system.");
        System.err.println("");
        System.err.println("pm list users: prints all users on the system.");
        System.err.println("");
        System.err.println("pm path: print the path to the .apk of the given PACKAGE.");
        System.err.println("");
        System.err.println("pm dump: print system state associated with the given PACKAGE.");
        System.err.println("");
        System.err.println("pm install: install a single legacy package");
        System.err.println("pm install-create: create an install session");
        System.err.println("    -l: forward lock application");
        System.err.println("    -r: replace existing application");
        System.err.println("    -t: allow test packages");
        System.err.println("    -i: specify the installer package name");
        System.err.println("    -s: install application on sdcard");
        System.err.println("    -f: install application on internal flash");
        System.err.println("    -d: allow version code downgrade");
        System.err.println("    -p: partial application install");
        System.err.println("    -g: grant all runtime permissions");
        System.err.println("    -S: size in bytes of entire session");
        System.err.println("");
        System.err.println("pm install-write: write a package into existing session; path may");
        System.err.println("  be '-' to read from stdin");
        System.err.println("    -S: size in bytes of package, required for stdin");
        System.err.println("");
        System.err.println("pm install-commit: perform install of fully staged session");
        System.err.println("pm install-abandon: abandon session");
        System.err.println("");
        System.err.println("pm set-installer: set installer package name");
        System.err.println("");
        System.err.println("pm uninstall: removes a package from the system. Options:");
        System.err.println("    -k: keep the data and cache directories around after package removal.");
        System.err.println("");
        System.err.println("pm clear: deletes all data associated with a package.");
        System.err.println("");
        System.err.println("pm enable, disable, disable-user, disable-until-used: these commands");
        System.err.println("  change the enabled state of a given package or component (written");
        System.err.println("  as \"package/class\").");
        System.err.println("");
        System.err.println("pm grant, revoke: these commands either grant or revoke permissions");
        System.err.println("    to apps. The permissions must be declared as used in the app's");
        System.err.println("    manifest, be runtime permissions (protection level dangerous),");
        System.err.println("    and the app targeting SDK greater than Lollipop MR1.");
        System.err.println("");
        System.err.println("pm reset-permissions: revert all runtime permissions to their default state.");
        System.err.println("");
        System.err.println("pm get-install-location: returns the current install location.");
        System.err.println("    0 [auto]: Let system decide the best location");
        System.err.println("    1 [internal]: Install on internal device storage");
        System.err.println("    2 [external]: Install on external media");
        System.err.println("");
        System.err.println("pm set-install-location: changes the default install location.");
        System.err.println("  NOTE: this is only intended for debugging; using this can cause");
        System.err.println("  applications to break and other undersireable behavior.");
        System.err.println("    0 [auto]: Let system decide the best location");
        System.err.println("    1 [internal]: Install on internal device storage");
        System.err.println("    2 [external]: Install on external media");
        System.err.println("");
        System.err.println("pm trim-caches: trim cache files to reach the given free space.");
        System.err.println("");
        System.err.println("pm create-user: create a new user with the given USER_NAME,");
        System.err.println("  printing the new user identifier of the user.");
        System.err.println("");
        System.err.println("pm remove-user: remove the user with the given USER_IDENTIFIER,");
        System.err.println("  deleting all data associated with that user");
        System.err.println("");
        return 1;
    }
}
