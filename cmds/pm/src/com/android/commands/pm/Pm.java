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

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.IPackageInstallObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.io.File;
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
        try {
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("-l")) {
                    // old compat
                } else if (opt.equals("-lf")) {
                    showApplicationPackage = true;
                } else if (opt.equals("-f")) {
                    showApplicationPackage = true;
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
            List<PackageInfo> packages = mPm.getInstalledPackages(0 /* all */);
            
            int count = packages.size();
            for (int p = 0 ; p < count ; p++) {
                PackageInfo info = packages.get(p);
                System.out.print("package:");
                if (showApplicationPackage) {
                    System.out.print(info.applicationInfo.sourceDir);
                    System.out.print("=");
                }
                System.out.println(info.packageName);
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
        Resources r = getResources(pii);
        if (r != null) {
            return r.getString(res);
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
    
    private String installFailureToString(int result) {
        String s;
        switch (result) {
        case PackageManager.INSTALL_FAILED_ALREADY_EXISTS:
            s = "INSTALL_FAILED_ALREADY_EXISTS";
            break;
        case PackageManager.INSTALL_FAILED_INVALID_APK:
            s = "INSTALL_FAILED_INVALID_APK";
            break;
        case PackageManager.INSTALL_FAILED_INVALID_URI:
            s = "INSTALL_FAILED_INVALID_URI";
            break;
        case PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE:
            s = "INSTALL_FAILED_INSUFFICIENT_STORAGE";
            break;
        case PackageManager.INSTALL_FAILED_DUPLICATE_PACKAGE:
            s = "INSTALL_FAILED_DUPLICATE_PACKAGE";
            break;
        case PackageManager.INSTALL_FAILED_NO_SHARED_USER:
            s = "INSTALL_FAILED_NO_SHARED_USER";
            break;
        case PackageManager.INSTALL_FAILED_UPDATE_INCOMPATIBLE:
            s = "INSTALL_FAILED_UPDATE_INCOMPATIBLE";
            break;
        case PackageManager.INSTALL_FAILED_SHARED_USER_INCOMPATIBLE:
            s = "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE";
            break;
        case PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY:
            s = "INSTALL_FAILED_MISSING_SHARED_LIBRARY";
            break;
        case PackageManager.INSTALL_FAILED_REPLACE_COULDNT_DELETE:
            s = "INSTALL_FAILED_REPLACE_COULDNT_DELETE";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_NOT_APK:
            s = "INSTALL_PARSE_FAILED_NOT_APK";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST:
            s = "INSTALL_PARSE_FAILED_BAD_MANIFEST";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION:
            s = "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_NO_CERTIFICATES:
            s = "INSTALL_PARSE_FAILED_NO_CERTIFICATES";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES:
            s = "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING:
            s = "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME:
            s = "INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID:
            s = "INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED:
            s = "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED";
            break;
        case PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY:
            s = "INSTALL_PARSE_FAILED_MANIFEST_EMPTY";
            break;
        case PackageManager.INSTALL_FAILED_OLDER_SDK:
            s = "INSTALL_FAILED_OLDER_SDK";
            break;
        default:
            s = Integer.toString(result);
        break;
        }
        return s;
    }
    
    private void runInstall() {
        int installFlags = 0;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-l")) {
                installFlags |= PackageManager.FORWARD_LOCK_PACKAGE;
            } else if (opt.equals("-r")) {
                installFlags |= PackageManager.REPLACE_EXISTING_PACKAGE;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return;
            }
        }

        String apkFilePath = nextArg();
        System.err.println("\tpkg: " + apkFilePath);
        if (apkFilePath == null) {
            System.err.println("Error: no package specified");
            showUsage();
            return;
        }

        PackageInstallObserver obs = new PackageInstallObserver();
        try {
            mPm.installPackage(Uri.fromFile(new File(apkFilePath)), obs, installFlags);
            
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
    
    class PackageDeleteObserver extends IPackageDeleteObserver.Stub {
        boolean finished;
        boolean result;
        
        public void packageDeleted(boolean succeeded) {
            synchronized (this) {
                finished = true;
                result = succeeded;
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
        System.err.println("usage: pm [list|path|install|uninstall]");
        System.err.println("       pm list packages [-f]");
        System.err.println("       pm list permission-groups");
        System.err.println("       pm list permissions [-g] [-f] [-d] [-u] [GROUP]");
        System.err.println("       pm list instrumentation [-f] [TARGET-PACKAGE]");        
        System.err.println("       pm path PACKAGE");
        System.err.println("       pm install [-l] [-r] PATH");
        System.err.println("       pm uninstall [-k] PACKAGE");
        System.err.println("");
        System.err.println("The list packages command prints all packages.  Use");
        System.err.println("the -f option to see their associated file.");
        System.err.println("");
        System.err.println("The list permission-groups command prints all known");
        System.err.println("permission groups.");
        System.err.println("");
        System.err.println("The list permissions command prints all known");
        System.err.println("permissions, optionally only those in GROUP.  Use");
        System.err.println("the -g option to organize by group.  Use");
        System.err.println("the -f option to print all information.  Use");
        System.err.println("the -s option for a short summary.  Use");
        System.err.println("the -d option to only list dangerous permissions.  Use");
        System.err.println("the -u option to list only the permissions users will see.");
        System.err.println("");
        System.err.println("The list instrumentation command prints all instrumentations,");
        System.err.println("or only those that target a specified package.  Use the -f option");
        System.err.println("to see their associated file.");
        System.err.println("");
        System.err.println("The path command prints the path to the .apk of a package.");
        System.err.println("");
        System.err.println("The install command installs a package to the system.  Use");
        System.err.println("the -l option to install the package with FORWARD_LOCK. Use");
        System.err.println("the -r option to reinstall an exisiting app, keeping its data.");
        System.err.println("");
        System.err.println("The uninstall command removes a package from the system. Use");
        System.err.println("the -k option to keep the data and cache directories around");
        System.err.println("after the package removal.");
    }
}
