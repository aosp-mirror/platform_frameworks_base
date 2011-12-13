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

import com.android.internal.content.PackageHelper;

import android.app.ActivityManagerNative;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;

public final class Pm {
    IPackageManager mPm;

    private WeakHashMap<String, Resources> mResourceCache
            = new WeakHashMap<String, Resources>();

    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private static final String PM_NOT_RUNNING_ERR =
        "Error: Could not access the Package Manager.  Is the system running?";
    private static final int ROOT_UID = 0;

    public static void main(String[] args) {
        new Pm().run(args);
    }

    public void run(String[] args) {
        boolean validCommand = false;
        if (args.length < 1) {
            showUsage();
            return;
        }

        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            System.err.println(PM_NOT_RUNNING_ERR);
            return;
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if ("list".equals(op)) {
            runList();
            return;
        }

        if ("path".equals(op)) {
            runPath();
            return;
        }

        if ("install".equals(op)) {
            runInstall();
            return;
        }

        if ("uninstall".equals(op)) {
            runUninstall();
            return;
        }

        if ("clear".equals(op)) {
            runClear();
            return;
        }

        if ("enable".equals(op)) {
            runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
            return;
        }

        if ("disable".equals(op)) {
            runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
            return;
        }

        if ("disable-user".equals(op)) {
            runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
            return;
        }

        if ("set-install-location".equals(op)) {
            runSetInstallLocation();
            return;
        }

        if ("get-install-location".equals(op)) {
            runGetInstallLocation();
            return;
        }

        if ("createUser".equals(op)) {
            runCreateUser();
            return;
        }

        if ("removeUser".equals(op)) {
            runRemoveUser();
            return;
        }

        try {
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("-l")) {
                    validCommand = true;
                    runListPackages(false);
                } else if (args[0].equalsIgnoreCase("-lf")){
                    validCommand = true;
                    runListPackages(true);
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("-p")) {
                    validCommand = true;
                    displayPackageFilePath(args[1]);
                }
            }
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
    private void runList() {
        String type = nextArg();
        if (type == null) {
            System.err.println("Error: didn't specify type of data to list");
            showUsage();
            return;
        }
        if ("package".equals(type) || "packages".equals(type)) {
            runListPackages(false);
        } else if ("permission-groups".equals(type)) {
            runListPermissionGroups();
        } else if ("permissions".equals(type)) {
            runListPermissions();
        } else if ("features".equals(type)) {
            runListFeatures();
        } else if ("libraries".equals(type)) {
            runListLibraries();
        } else if ("instrumentation".equals(type)) {
            runListInstrumentation();
        } else {
            System.err.println("Error: unknown list type '" + type + "'");
            showUsage();
        }
    }

    /**
     * Lists all the installed packages.
     */
    private void runListPackages(boolean showApplicationPackage) {
        int getFlags = 0;
        boolean listDisabled = false, listEnabled = false;
        boolean listSystem = false, listThirdParty = false;
        boolean listInstaller = false;
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
                } else if (opt.equals("-u")) {
                    getFlags |= PackageManager.GET_UNINSTALLED_PACKAGES;
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    showUsage();
                    return;
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Error: " + ex.toString());
            showUsage();
            return;
        }

        String filter = nextArg();

        try {
            final List<PackageInfo> packages = getInstalledPackages(mPm, getFlags);

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
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    @SuppressWarnings("unchecked")
    private List<PackageInfo> getInstalledPackages(IPackageManager pm, int flags)
            throws RemoteException {
        final List<PackageInfo> packageInfos = new ArrayList<PackageInfo>();
        PackageInfo lastItem = null;
        ParceledListSlice<PackageInfo> slice;

        do {
            final String lastKey = lastItem != null ? lastItem.packageName : null;
            slice = pm.getInstalledPackages(flags, lastKey);
            lastItem = slice.populateList(packageInfos, PackageInfo.CREATOR);
        } while (!slice.isLastSlice());

        return packageInfos;
    }

    /**
     * Lists all of the features supported by the current device.
     *
     * pm list features
     */
    private void runListFeatures() {
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
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    /**
     * Lists all of the libraries supported by the current device.
     *
     * pm list libraries
     */
    private void runListLibraries() {
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
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    /**
     * Lists all of the installed instrumentation, or all for a given package
     *
     * pm list instrumentation [package] [-f]
     */
    private void runListInstrumentation() {
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
                    showUsage();
                    return;
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Error: " + ex.toString());
            showUsage();
            return;
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
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    /**
     * Lists all the known permission groups.
     */
    private void runListPermissionGroups() {
        try {
            List<PermissionGroupInfo> pgs = mPm.getAllPermissionGroups(0);

            int count = pgs.size();
            for (int p = 0 ; p < count ; p++) {
                PermissionGroupInfo pgi = pgs.get(p);
                System.out.print("permission group:");
                System.out.println(pgi.name);
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    private String loadText(PackageItemInfo pii, int res, CharSequence nonLocalized) {
        if (nonLocalized != null) {
            return nonLocalized.toString();
        }
        if (res != 0) {
            Resources r = getResources(pii);
            if (r != null) {
                return r.getString(res);
            }
        }
        return null;
    }

    /**
     * Lists all the permissions in a group.
     */
    private void runListPermissions() {
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
                    showUsage();
                    return;
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
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
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
                if (pi.protectionLevel < startProtectionLevel
                        || pi.protectionLevel > endProtectionLevel) {
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
                        String protLevel = "unknown";
                        switch(pi.protectionLevel) {
                            case PermissionInfo.PROTECTION_DANGEROUS:
                                protLevel = "dangerous";
                                break;
                            case PermissionInfo.PROTECTION_NORMAL:
                                protLevel = "normal";
                                break;
                            case PermissionInfo.PROTECTION_SIGNATURE:
                                protLevel = "signature";
                                break;
                            case PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM:
                                protLevel = "signatureOrSystem";
                                break;
                        }
                        System.out.println(prefix + "  protectionLevel:" + protLevel);
                    }
                }
            }

            if (summary) {
                System.out.println("");
            }
        }
    }

    private void runPath() {
        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return;
        }
        displayPackageFilePath(pkg);
    }

    class PackageInstallObserver extends IPackageInstallObserver.Stub {
        boolean finished;
        int result;

        public void packageInstalled(String name, int status) {
            synchronized( this) {
                finished = true;
                result = status;
                notifyAll();
            }
        }
    }

    /**
     * Converts a failure code into a string by using reflection to find a matching constant
     * in PackageManager.
     */
    private String installFailureToString(int result) {
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
                                return fieldName;
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

    private void runSetInstallLocation() {
        int loc;

        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no install location specified.");
            showUsage();
            return;
        }
        try {
            loc = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println("Error: install location has to be a number.");
            showUsage();
            return;
        }
        try {
            if (!mPm.setInstallLocation(loc)) {
                System.err.println("Error: install location has to be a number.");
                showUsage();
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    private void runGetInstallLocation() {
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
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    private void runInstall() {
        int installFlags = 0;
        String installerPackageName = null;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-l")) {
                installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
            } else if (opt.equals("-r")) {
                installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
            } else if (opt.equals("-i")) {
                installerPackageName = nextOptionData();
                if (installerPackageName == null) {
                    System.err.println("Error: no value specified for -i");
                    showUsage();
                    return;
                }
            } else if (opt.equals("-t")) {
                installFlags |= PackageManager.INSTALL_ALLOW_TEST;
            } else if (opt.equals("-s")) {
                // Override if -s option is specified.
                installFlags |= PackageManager.INSTALL_EXTERNAL;
            } else if (opt.equals("-f")) {
                // Override if -s option is specified.
                installFlags |= PackageManager.INSTALL_INTERNAL;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return;
            }
        }

        final Uri apkURI;
        final Uri verificationURI;

        // Populate apkURI, must be present
        final String apkFilePath = nextArg();
        System.err.println("\tpkg: " + apkFilePath);
        if (apkFilePath != null) {
            apkURI = Uri.fromFile(new File(apkFilePath));
        } else {
            System.err.println("Error: no package specified");
            showUsage();
            return;
        }

        // Populate verificationURI, optionally present
        final String verificationFilePath = nextArg();
        if (verificationFilePath != null) {
            System.err.println("\tver: " + verificationFilePath);
            verificationURI = Uri.fromFile(new File(verificationFilePath));
        } else {
            verificationURI = null;
        }

        PackageInstallObserver obs = new PackageInstallObserver();
        try {
            mPm.installPackageWithVerification(apkURI, obs, installFlags, installerPackageName,
                    verificationURI, null);

            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
                if (obs.result == PackageManager.INSTALL_SUCCEEDED) {
                    System.out.println("Success");
                } else {
                    System.err.println("Failure ["
                            + installFailureToString(obs.result)
                            + "]");
                }
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    public void runCreateUser() {
        // Need to be run as root
        if (Process.myUid() != ROOT_UID) {
            System.err.println("Error: createUser must be run as root");
            return;
        }
        String name;
        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no user name specified.");
            showUsage();
            return;
        }
        name = arg;
        try {
            if (mPm.createUser(name, 0) == null) {
                System.err.println("Error: couldn't create user.");
                showUsage();
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }

    }

    public void runRemoveUser() {
        // Need to be run as root
        if (Process.myUid() != ROOT_UID) {
            System.err.println("Error: removeUser must be run as root");
            return;
        }
        int userId;
        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no user id specified.");
            showUsage();
            return;
        }
        try {
            userId = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println("Error: user id has to be a number.");
            showUsage();
            return;
        }
        try {
            if (!mPm.removeUser(userId)) {
                System.err.println("Error: couldn't remove user.");
                showUsage();
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        boolean finished;
        boolean result;

        public void packageDeleted(String packageName, int returnCode) {
            synchronized (this) {
                finished = true;
                result = returnCode == PackageManager.DELETE_SUCCEEDED;
                notifyAll();
            }
        }
    }

    private void runUninstall() {
        int unInstallFlags = 0;

        String opt = nextOption();
        if (opt != null && opt.equals("-k")) {
            unInstallFlags = PackageManager.DONT_DELETE_DATA;
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return;
        }
        boolean result = deletePackage(pkg, unInstallFlags);
        if (result) {
            System.out.println("Success");
        } else {
            System.out.println("Failure");
        }
    }

    private boolean deletePackage(String pkg, int unInstallFlags) {
        PackageDeleteObserver obs = new PackageDeleteObserver();
        try {
            mPm.deletePackage(pkg, obs, unInstallFlags);

            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
        return obs.result;
    }

    class ClearDataObserver extends IPackageDataObserver.Stub {
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

    private void runClear() {
        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return;
        }

        ClearDataObserver obs = new ClearDataObserver();
        try {
            if (!ActivityManagerNative.getDefault().clearApplicationUserData(pkg, obs)) {
                System.err.println("Failed");
            }

            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (obs.result) {
                System.err.println("Success");
            } else {
                System.err.println("Failed");
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
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
        }
        return "unknown";
    }

    private void runSetEnabledSetting(int state) {
        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package or component specified");
            showUsage();
            return;
        }
        ComponentName cn = ComponentName.unflattenFromString(pkg);
        if (cn == null) {
            try {
                mPm.setApplicationEnabledSetting(pkg, state, 0);
                System.err.println("Package " + pkg + " new state: "
                        + enabledSettingToString(
                                mPm.getApplicationEnabledSetting(pkg)));
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
            }
        } else {
            try {
                mPm.setComponentEnabledSetting(cn, state, 0);
                System.err.println("Component " + cn.toShortString() + " new state: "
                        + enabledSettingToString(
                                mPm.getComponentEnabledSetting(cn)));
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
            }
        }
    }

    /**
     * Displays the package file for a package.
     * @param pckg
     */
    private void displayPackageFilePath(String pckg) {
        try {
            PackageInfo info = mPm.getPackageInfo(pckg, 0);
            if (info != null && info.applicationInfo != null) {
                System.out.print("package:");
                System.out.println(info.applicationInfo.sourceDir);
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
    }

    private Resources getResources(PackageItemInfo pii) {
        Resources res = mResourceCache.get(pii.packageName);
        if (res != null) return res;

        try {
            ApplicationInfo ai = mPm.getApplicationInfo(pii.packageName, 0);
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

    private static void showUsage() {
        System.err.println("usage: pm list packages [-f] [-d] [-e] [-s] [-3] [-i] [-u] [FILTER]");
        System.err.println("       pm list permission-groups");
        System.err.println("       pm list permissions [-g] [-f] [-d] [-u] [GROUP]");
        System.err.println("       pm list instrumentation [-f] [TARGET-PACKAGE]");
        System.err.println("       pm list features");
        System.err.println("       pm list libraries");
        System.err.println("       pm path PACKAGE");
        System.err.println("       pm install [-l] [-r] [-t] [-i INSTALLER_PACKAGE_NAME] [-s] [-f] PATH");
        System.err.println("       pm uninstall [-k] PACKAGE");
        System.err.println("       pm clear PACKAGE");
        System.err.println("       pm enable PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-user PACKAGE_OR_COMPONENT");
        System.err.println("       pm set-install-location [0/auto] [1/internal] [2/external]");
        System.err.println("       pm get-install-location");
        System.err.println("       pm createUser USER_NAME");
        System.err.println("       pm removeUser USER_ID");
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
        System.err.println("pm path: print the path to the .apk of the given PACKAGE.");
        System.err.println("");
        System.err.println("pm install: installs a package to the system.  Options:");
        System.err.println("    -l: install the package with FORWARD_LOCK.");
        System.err.println("    -r: reinstall an exisiting app, keeping its data.");
        System.err.println("    -t: allow test .apks to be installed.");
        System.err.println("    -i: specify the installer package name.");
        System.err.println("    -s: install package on sdcard.");
        System.err.println("    -f: install package on internal flash.");
        System.err.println("");
        System.err.println("pm uninstall: removes a package from the system. Options:");
        System.err.println("    -k: keep the data and cache directories around after package removal.");
        System.err.println("");
        System.err.println("pm clear: deletes all data associated with a package.");
        System.err.println("");
        System.err.println("pm enable, disable, disable-user: these commands change the enabled state");
        System.err.println("  of a given package or component (written as \"package/class\").");
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
    }
}
