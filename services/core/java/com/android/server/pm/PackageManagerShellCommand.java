/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.DexMetadataHelper;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.PrintWriterPrinter;
import com.android.internal.content.PackageHelper;
import com.android.internal.util.SizedInputStream;
import com.android.server.SystemConfig;

import dalvik.system.DexFile;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class PackageManagerShellCommand extends ShellCommand {
    /** Path for streaming APK content */
    private static final String STDIN_PATH = "-";
    /** Whether or not APK content must be streamed from stdin */
    private static final boolean FORCE_STREAM_INSTALL = true;

    final IPackageManager mInterface;
    final private WeakHashMap<String, Resources> mResourceCache =
            new WeakHashMap<String, Resources>();
    int mTargetUser;
    boolean mBrief;
    boolean mComponents;

    PackageManagerShellCommand(PackageManagerService service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "install":
                    return runInstall();
                case "install-abandon":
                case "install-destroy":
                    return runInstallAbandon();
                case "install-commit":
                    return runInstallCommit();
                case "install-create":
                    return runInstallCreate();
                case "install-remove":
                    return runInstallRemove();
                case "install-write":
                    return runInstallWrite();
                case "install-existing":
                    return runInstallExisting();
                case "compile":
                    return runCompile();
                case "reconcile-secondary-dex-files":
                    return runreconcileSecondaryDexFiles();
                case "bg-dexopt-job":
                    return runDexoptJob();
                case "dump-profiles":
                    return runDumpProfiles();
                case "list":
                    return runList();
                case "uninstall":
                    return runUninstall();
                case "resolve-activity":
                    return runResolveActivity();
                case "query-activities":
                    return runQueryIntentActivities();
                case "query-services":
                    return runQueryIntentServices();
                case "query-receivers":
                    return runQueryIntentReceivers();
                case "suspend":
                    return runSuspend(true);
                case "unsuspend":
                    return runSuspend(false);
                case "set-home-activity":
                    return runSetHomeActivity();
                case "get-privapp-permissions":
                    return runGetPrivappPermissions();
                case "get-privapp-deny-permissions":
                    return runGetPrivappDenyPermissions();
                case "get-instantapp-resolver":
                    return runGetInstantAppResolver();
                case "has-feature":
                    return runHasFeature();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private void setParamsSize(InstallParams params, String inPath) {
        // If we're forced to stream the package, the params size
        // must be set via command-line argument. There's nothing
        // to do here.
        if (FORCE_STREAM_INSTALL) {
            return;
        }
        final PrintWriter pw = getOutPrintWriter();
        if (params.sessionParams.sizeBytes == -1 && !STDIN_PATH.equals(inPath)) {
            File file = new File(inPath);
            if (file.isFile()) {
                try {
                    ApkLite baseApk = PackageParser.parseApkLite(file, 0);
                    PackageLite pkgLite = new PackageLite(null, baseApk, null, null, null, null,
                            null, null);
                    params.sessionParams.setSize(PackageHelper.calculateInstalledSize(
                            pkgLite, false, params.sessionParams.abiOverride));
                } catch (PackageParserException | IOException e) {
                    pw.println("Error: Failed to parse APK file: " + file);
                    throw new IllegalArgumentException(
                            "Error: Failed to parse APK file: " + file, e);
                }
            } else {
                pw.println("Error: Can't open non-file: " + inPath);
                throw new IllegalArgumentException("Error: Can't open non-file: " + inPath);
            }
        }
    }

    private int runInstall() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final InstallParams params = makeInstallParams();
        final String inPath = getNextArg();

        setParamsSize(params, inPath);
        final int sessionId = doCreateSession(params.sessionParams,
                params.installerPackageName, params.userId);
        boolean abandonSession = true;
        try {
            if (inPath == null && params.sessionParams.sizeBytes == -1) {
                pw.println("Error: must either specify a package size or an APK file");
                return 1;
            }
            if (doWriteSplit(sessionId, inPath, params.sessionParams.sizeBytes, "base.apk",
                    false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            if (doCommitSession(sessionId, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            abandonSession = false;
            pw.println("Success");
            return 0;
        } finally {
            if (abandonSession) {
                try {
                    doAbandonSession(sessionId, false /*logSuccess*/);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private int runSuspend(boolean suspendedState) {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        try {
            mInterface.setPackagesSuspendedAsUser(new String[]{packageName}, suspendedState,
                    userId);
            pw.println("Package " + packageName + " new suspended state: "
                    + mInterface.isPackageSuspendedForUser(packageName, userId));
            return 0;
        } catch (RemoteException | IllegalArgumentException e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runInstallAbandon() throws RemoteException {
        final int sessionId = Integer.parseInt(getNextArg());
        return doAbandonSession(sessionId, true /*logSuccess*/);
    }

    private int runInstallCommit() throws RemoteException {
        final int sessionId = Integer.parseInt(getNextArg());
        return doCommitSession(sessionId, true /*logSuccess*/);
    }

    private int runInstallCreate() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final InstallParams installParams = makeInstallParams();
        final int sessionId = doCreateSession(installParams.sessionParams,
                installParams.installerPackageName, installParams.userId);

        // NOTE: adb depends on parsing this string
        pw.println("Success: created install session [" + sessionId + "]");
        return 0;
    }

    private int runInstallWrite() throws RemoteException {
        long sizeBytes = -1;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("-S")) {
                sizeBytes = Long.parseLong(getNextArg());
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }

        final int sessionId = Integer.parseInt(getNextArg());
        final String splitName = getNextArg();
        final String path = getNextArg();
        return doWriteSplit(sessionId, path, sizeBytes, splitName, true /*logSuccess*/);
    }

    private int runInstallRemove() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();

        final int sessionId = Integer.parseInt(getNextArg());

        final String splitName = getNextArg();
        if (splitName == null) {
            pw.println("Error: split name not specified");
            return 1;
        }
        return doRemoveSplit(sessionId, splitName, true /*logSuccess*/);
    }

    private int runInstallExisting() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        int installFlags = 0;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--ephemeral":
                case "--instant":
                    installFlags |= PackageManager.INSTALL_INSTANT_APP;
                    installFlags &= ~PackageManager.INSTALL_FULL_APP;
                    break;
                case "--full":
                    installFlags &= ~PackageManager.INSTALL_INSTANT_APP;
                    installFlags |= PackageManager.INSTALL_FULL_APP;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        try {
            final int res = mInterface.installExistingPackageAsUser(packageName, userId,
                    installFlags, PackageManager.INSTALL_REASON_UNKNOWN);
            if (res == PackageManager.INSTALL_FAILED_INVALID_URI) {
                throw new NameNotFoundException("Package " + packageName + " doesn't exist");
            }
            pw.println("Package " + packageName + " installed for user: " + userId);
            return 0;
        } catch (RemoteException | NameNotFoundException e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runCompile() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean checkProfiles = SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false);
        boolean forceCompilation = false;
        boolean allPackages = false;
        boolean clearProfileData = false;
        String compilerFilter = null;
        String compilationReason = null;
        String checkProfilesRaw = null;
        boolean secondaryDex = false;
        String split = null;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-a":
                    allPackages = true;
                    break;
                case "-c":
                    clearProfileData = true;
                    break;
                case "-f":
                    forceCompilation = true;
                    break;
                case "-m":
                    compilerFilter = getNextArgRequired();
                    break;
                case "-r":
                    compilationReason = getNextArgRequired();
                    break;
                case "--check-prof":
                    checkProfilesRaw = getNextArgRequired();
                    break;
                case "--reset":
                    forceCompilation = true;
                    clearProfileData = true;
                    compilationReason = "install";
                    break;
                case "--secondary-dex":
                    secondaryDex = true;
                    break;
                case "--split":
                    split = getNextArgRequired();
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        if (checkProfilesRaw != null) {
            if ("true".equals(checkProfilesRaw)) {
                checkProfiles = true;
            } else if ("false".equals(checkProfilesRaw)) {
                checkProfiles = false;
            } else {
                pw.println("Invalid value for \"--check-prof\". Expected \"true\" or \"false\".");
                return 1;
            }
        }

        if (compilerFilter != null && compilationReason != null) {
            pw.println("Cannot use compilation filter (\"-m\") and compilation reason (\"-r\") " +
                    "at the same time");
            return 1;
        }
        if (compilerFilter == null && compilationReason == null) {
            pw.println("Cannot run without any of compilation filter (\"-m\") and compilation " +
                    "reason (\"-r\") at the same time");
            return 1;
        }

        if (allPackages && split != null) {
            pw.println("-a cannot be specified together with --split");
            return 1;
        }

        if (secondaryDex && split != null) {
            pw.println("--secondary-dex cannot be specified together with --split");
            return 1;
        }

        String targetCompilerFilter;
        if (compilerFilter != null) {
            if (!DexFile.isValidCompilerFilter(compilerFilter)) {
                pw.println("Error: \"" + compilerFilter +
                        "\" is not a valid compilation filter.");
                return 1;
            }
            targetCompilerFilter = compilerFilter;
        } else {
            int reason = -1;
            for (int i = 0; i < PackageManagerServiceCompilerMapping.REASON_STRINGS.length; i++) {
                if (PackageManagerServiceCompilerMapping.REASON_STRINGS[i].equals(
                        compilationReason)) {
                    reason = i;
                    break;
                }
            }
            if (reason == -1) {
                pw.println("Error: Unknown compilation reason: " + compilationReason);
                return 1;
            }
            targetCompilerFilter =
                    PackageManagerServiceCompilerMapping.getCompilerFilterForReason(reason);
        }


        List<String> packageNames = null;
        if (allPackages) {
            packageNames = mInterface.getAllPackages();
        } else {
            String packageName = getNextArg();
            if (packageName == null) {
                pw.println("Error: package name not specified");
                return 1;
            }
            packageNames = Collections.singletonList(packageName);
        }

        List<String> failedPackages = new ArrayList<>();
        int index = 0;
        for (String packageName : packageNames) {
            if (clearProfileData) {
                mInterface.clearApplicationProfileData(packageName);
            }

            if (allPackages) {
                pw.println(++index + "/" + packageNames.size() + ": " + packageName);
                pw.flush();
            }

            boolean result = secondaryDex
                    ? mInterface.performDexOptSecondary(packageName,
                            targetCompilerFilter, forceCompilation)
                    : mInterface.performDexOptMode(packageName,
                            checkProfiles, targetCompilerFilter, forceCompilation,
                            true /* bootComplete */, split);
            if (!result) {
                failedPackages.add(packageName);
            }
        }

        if (failedPackages.isEmpty()) {
            pw.println("Success");
            return 0;
        } else if (failedPackages.size() == 1) {
            pw.println("Failure: package " + failedPackages.get(0) + " could not be compiled");
            return 1;
        } else {
            pw.print("Failure: the following packages could not be compiled: ");
            boolean is_first = true;
            for (String packageName : failedPackages) {
                if (is_first) {
                    is_first = false;
                } else {
                    pw.print(", ");
                }
                pw.print(packageName);
            }
            pw.println();
            return 1;
        }
    }

    private int runreconcileSecondaryDexFiles() throws RemoteException {
        String packageName = getNextArg();
        mInterface.reconcileSecondaryDexFiles(packageName);
        return 0;
    }

    private int runDexoptJob() throws RemoteException {
        boolean result = mInterface.runBackgroundDexoptJob();
        return result ? 0 : -1;
    }

    private int runDumpProfiles() throws RemoteException {
        String packageName = getNextArg();
        mInterface.dumpProfiles(packageName);
        return 0;
    }

    private int runList() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to list");
            return -1;
        }
        switch(type) {
            case "features":
                return runListFeatures();
            case "instrumentation":
                return runListInstrumentation();
            case "libraries":
                return runListLibraries();
            case "package":
            case "packages":
                return runListPackages(false /*showSourceDir*/);
            case "permission-groups":
                return runListPermissionGroups();
            case "permissions":
                return runListPermissions();
        }
        pw.println("Error: unknown list type '" + type + "'");
        return -1;
    }

    private int runListFeatures() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<FeatureInfo> list = mInterface.getSystemAvailableFeatures().getList();

        // sort by name
        Collections.sort(list, new Comparator<FeatureInfo>() {
            public int compare(FeatureInfo o1, FeatureInfo o2) {
                if (o1.name == o2.name) return 0;
                if (o1.name == null) return -1;
                if (o2.name == null) return 1;
                return o1.name.compareTo(o2.name);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            FeatureInfo fi = list.get(p);
            pw.print("feature:");
            if (fi.name != null) {
                pw.print(fi.name);
                if (fi.version > 0) {
                    pw.print("=");
                    pw.print(fi.version);
                }
                pw.println();
            } else {
                pw.println("reqGlEsVersion=0x"
                    + Integer.toHexString(fi.reqGlEsVersion));
            }
        }
        return 0;
    }

    private int runListInstrumentation() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean showSourceDir = false;
        String targetPackage = null;

        try {
            String opt;
            while ((opt = getNextArg()) != null) {
                switch (opt) {
                    case "-f":
                        showSourceDir = true;
                        break;
                    default:
                        if (opt.charAt(0) != '-') {
                            targetPackage = opt;
                        } else {
                            pw.println("Error: Unknown option: " + opt);
                            return -1;
                        }
                        break;
                }
            }
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        final List<InstrumentationInfo> list =
                mInterface.queryInstrumentation(targetPackage, 0 /*flags*/).getList();

        // sort by target package
        Collections.sort(list, new Comparator<InstrumentationInfo>() {
            public int compare(InstrumentationInfo o1, InstrumentationInfo o2) {
                return o1.targetPackage.compareTo(o2.targetPackage);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            final InstrumentationInfo ii = list.get(p);
            pw.print("instrumentation:");
            if (showSourceDir) {
                pw.print(ii.sourceDir);
                pw.print("=");
            }
            final ComponentName cn = new ComponentName(ii.packageName, ii.name);
            pw.print(cn.flattenToShortString());
            pw.print(" (target=");
            pw.print(ii.targetPackage);
            pw.println(")");
        }
        return 0;
    }

    private int runListLibraries() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<String> list = new ArrayList<String>();
        final String[] rawList = mInterface.getSystemSharedLibraryNames();
        for (int i = 0; i < rawList.length; i++) {
            list.add(rawList[i]);
        }

        // sort by name
        Collections.sort(list, new Comparator<String>() {
            public int compare(String o1, String o2) {
                if (o1 == o2) return 0;
                if (o1 == null) return -1;
                if (o2 == null) return 1;
                return o1.compareTo(o2);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            String lib = list.get(p);
            pw.print("library:");
            pw.println(lib);
        }
        return 0;
    }

    private int runListPackages(boolean showSourceDir) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int getFlags = 0;
        boolean listDisabled = false, listEnabled = false;
        boolean listSystem = false, listThirdParty = false;
        boolean listInstaller = false;
        boolean showUid = false;
        boolean showVersionCode = false;
        int uid = -1;
        int userId = UserHandle.USER_SYSTEM;
        try {
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "-d":
                        listDisabled = true;
                        break;
                    case "-e":
                        listEnabled = true;
                        break;
                    case "-f":
                        showSourceDir = true;
                        break;
                    case "-i":
                        listInstaller = true;
                        break;
                    case "-l":
                        // old compat
                        break;
                    case "-s":
                        listSystem = true;
                        break;
                    case "-U":
                        showUid = true;
                        break;
                    case "-u":
                        getFlags |= PackageManager.MATCH_UNINSTALLED_PACKAGES;
                        break;
                    case "-3":
                        listThirdParty = true;
                        break;
                    case "--show-versioncode":
                        showVersionCode = true;
                        break;
                    case "--user":
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    case "--uid":
                        showUid = true;
                        uid = Integer.parseInt(getNextArgRequired());
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return -1;
                }
            }
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        final String filter = getNextArg();

        @SuppressWarnings("unchecked")
        final ParceledListSlice<PackageInfo> slice =
                mInterface.getInstalledPackages(getFlags, userId);
        final List<PackageInfo> packages = slice.getList();

        final int count = packages.size();
        for (int p = 0; p < count; p++) {
            final PackageInfo info = packages.get(p);
            if (filter != null && !info.packageName.contains(filter)) {
                continue;
            }
            if (uid != -1 && info.applicationInfo.uid != uid) {
                continue;
            }
            final boolean isSystem =
                    (info.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0;
            if ((!listDisabled || !info.applicationInfo.enabled) &&
                    (!listEnabled || info.applicationInfo.enabled) &&
                    (!listSystem || isSystem) &&
                    (!listThirdParty || !isSystem)) {
                pw.print("package:");
                if (showSourceDir) {
                    pw.print(info.applicationInfo.sourceDir);
                    pw.print("=");
                }
                pw.print(info.packageName);
                if (showVersionCode) {
                    pw.print(" versionCode:");
                    pw.print(info.applicationInfo.versionCode);
                }
                if (listInstaller) {
                    pw.print("  installer=");
                    pw.print(mInterface.getInstallerPackageName(info.packageName));
                }
                if (showUid) {
                    pw.print(" uid:");
                    pw.print(info.applicationInfo.uid);
                }
                pw.println();
            }
        }
        return 0;
    }

    private int runListPermissionGroups() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<PermissionGroupInfo> pgs = mInterface.getAllPermissionGroups(0).getList();

        final int count = pgs.size();
        for (int p = 0; p < count ; p++) {
            final PermissionGroupInfo pgi = pgs.get(p);
            pw.print("permission group:");
            pw.println(pgi.name);
        }
        return 0;
    }

    private int runListPermissions() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean labels = false;
        boolean groups = false;
        boolean userOnly = false;
        boolean summary = false;
        boolean dangerousOnly = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-d":
                    dangerousOnly = true;
                    break;
                case "-f":
                    labels = true;
                    break;
                case "-g":
                    groups = true;
                    break;
                case "-s":
                    groups = true;
                    labels = true;
                    summary = true;
                    break;
                case "-u":
                    userOnly = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final ArrayList<String> groupList = new ArrayList<String>();
        if (groups) {
            final List<PermissionGroupInfo> infos =
                    mInterface.getAllPermissionGroups(0 /*flags*/).getList();
            final int count = infos.size();
            for (int i = 0; i < count; i++) {
                groupList.add(infos.get(i).name);
            }
            groupList.add(null);
        } else {
            final String grp = getNextArg();
            groupList.add(grp);
        }

        if (dangerousOnly) {
            pw.println("Dangerous Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    PermissionInfo.PROTECTION_DANGEROUS,
                    PermissionInfo.PROTECTION_DANGEROUS);
            if (userOnly) {
                pw.println("Normal Permissions:");
                pw.println("");
                doListPermissions(groupList, groups, labels, summary,
                        PermissionInfo.PROTECTION_NORMAL,
                        PermissionInfo.PROTECTION_NORMAL);
            }
        } else if (userOnly) {
            pw.println("Dangerous and Normal Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    PermissionInfo.PROTECTION_NORMAL,
                    PermissionInfo.PROTECTION_DANGEROUS);
        } else {
            pw.println("All Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    -10000, 10000);
        }
        return 0;
    }

    private int runUninstall() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int flags = 0;
        int userId = UserHandle.USER_ALL;
        int versionCode = PackageManager.VERSION_CODE_HIGHEST;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-k":
                    flags |= PackageManager.DELETE_KEEP_DATA;
                    break;
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--versionCode":
                    versionCode = Integer.parseInt(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        // if a split is specified, just remove it and not the whole package
        final String splitName = getNextArg();
        if (splitName != null) {
            return runRemoveSplit(packageName, splitName);
        }

        userId = translateUserId(userId, "runUninstall");
        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_SYSTEM;
            flags |= PackageManager.DELETE_ALL_USERS;
        } else {
            final PackageInfo info = mInterface.getPackageInfo(packageName,
                    PackageManager.MATCH_STATIC_SHARED_LIBRARIES, userId);
            if (info == null) {
                pw.println("Failure [not installed for " + userId + "]");
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
        mInterface.getPackageInstaller().uninstall(new VersionedPackage(packageName,
                versionCode), null /*callerPackageName*/, flags,
                receiver.getIntentSender(), userId);

        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            pw.println("Success");
            return 0;
        } else {
            pw.println("Failure ["
                    + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            return 1;
        }
    }

    private int runRemoveSplit(String packageName, String splitName) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final SessionParams sessionParams = new SessionParams(SessionParams.MODE_INHERIT_EXISTING);
        sessionParams.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
        sessionParams.appPackageName = packageName;
        final int sessionId =
                doCreateSession(sessionParams, null /*installerPackageName*/, UserHandle.USER_ALL);
        boolean abandonSession = true;
        try {
            if (doRemoveSplit(sessionId, splitName, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            if (doCommitSession(sessionId, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            abandonSession = false;
            pw.println("Success");
            return 0;
        } finally {
            if (abandonSession) {
                try {
                    doAbandonSession(sessionId, false /*logSuccess*/);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private Intent parseIntentAndUser() throws URISyntaxException {
        mTargetUser = UserHandle.USER_CURRENT;
        mBrief = false;
        mComponents = false;
        Intent intent = Intent.parseCommandArgs(this, new Intent.CommandOptionHandler() {
            @Override
            public boolean handleOption(String opt, ShellCommand cmd) {
                if ("--user".equals(opt)) {
                    mTargetUser = UserHandle.parseUserArg(cmd.getNextArgRequired());
                    return true;
                } else if ("--brief".equals(opt)) {
                    mBrief = true;
                    return true;
                } else if ("--components".equals(opt)) {
                    mComponents = true;
                    return true;
                }
                return false;
            }
        });
        mTargetUser = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), mTargetUser, false, false, null, null);
        return intent;
    }

    private void printResolveInfo(PrintWriterPrinter pr, String prefix, ResolveInfo ri,
            boolean brief, boolean components) {
        if (brief || components) {
            final ComponentName comp;
            if (ri.activityInfo != null) {
                comp = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            } else if (ri.serviceInfo != null) {
                comp = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            } else if (ri.providerInfo != null) {
                comp = new ComponentName(ri.providerInfo.packageName, ri.providerInfo.name);
            } else {
                comp = null;
            }
            if (comp != null) {
                if (!components) {
                    pr.println(prefix + "priority=" + ri.priority
                            + " preferredOrder=" + ri.preferredOrder
                            + " match=0x" + Integer.toHexString(ri.match)
                            + " specificIndex=" + ri.specificIndex
                            + " isDefault=" + ri.isDefault);
                }
                pr.println(prefix + comp.flattenToShortString());
                return;
            }
        }
        ri.dump(pr, prefix);
    }

    private int runResolveActivity() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            ResolveInfo ri = mInterface.resolveIntent(intent, intent.getType(), 0, mTargetUser);
            PrintWriter pw = getOutPrintWriter();
            if (ri == null) {
                pw.println("No activity found");
            } else {
                PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                printResolveInfo(pr, "", ri, mBrief, mComponents);
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runQueryIntentActivities() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            List<ResolveInfo> result = mInterface.queryIntentActivities(intent, intent.getType(), 0,
                    mTargetUser).getList();
            PrintWriter pw = getOutPrintWriter();
            if (result == null || result.size() <= 0) {
                pw.println("No activities found");
            } else {
                if (!mComponents) {
                    pw.print(result.size()); pw.println(" activities found:");
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        pw.print("  Activity #"); pw.print(i); pw.println(":");
                        printResolveInfo(pr, "    ", result.get(i), mBrief, mComponents);
                    }
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        printResolveInfo(pr, "", result.get(i), mBrief, mComponents);
                    }
                }
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runQueryIntentServices() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            List<ResolveInfo> result = mInterface.queryIntentServices(intent, intent.getType(), 0,
                    mTargetUser).getList();
            PrintWriter pw = getOutPrintWriter();
            if (result == null || result.size() <= 0) {
                pw.println("No services found");
            } else {
                if (!mComponents) {
                    pw.print(result.size()); pw.println(" services found:");
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        pw.print("  Service #"); pw.print(i); pw.println(":");
                        printResolveInfo(pr, "    ", result.get(i), mBrief, mComponents);
                    }
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        printResolveInfo(pr, "", result.get(i), mBrief, mComponents);
                    }
                }
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runQueryIntentReceivers() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            List<ResolveInfo> result = mInterface.queryIntentReceivers(intent, intent.getType(), 0,
                    mTargetUser).getList();
            PrintWriter pw = getOutPrintWriter();
            if (result == null || result.size() <= 0) {
                pw.println("No receivers found");
            } else {
                if (!mComponents) {
                    pw.print(result.size()); pw.println(" receivers found:");
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        pw.print("  Receiver #"); pw.print(i); pw.println(":");
                        printResolveInfo(pr, "    ", result.get(i), mBrief, mComponents);
                    }
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        printResolveInfo(pr, "", result.get(i), mBrief, mComponents);
                    }
                }
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private static class InstallParams {
        SessionParams sessionParams;
        String installerPackageName;
        int userId = UserHandle.USER_ALL;
    }

    private InstallParams makeInstallParams() {
        final SessionParams sessionParams = new SessionParams(SessionParams.MODE_FULL_INSTALL);
        final InstallParams params = new InstallParams();
        params.sessionParams = sessionParams;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-l":
                    sessionParams.installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
                    break;
                case "-r":
                    sessionParams.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
                    break;
                case "-i":
                    params.installerPackageName = getNextArg();
                    if (params.installerPackageName == null) {
                        throw new IllegalArgumentException("Missing installer package");
                    }
                    break;
                case "-t":
                    sessionParams.installFlags |= PackageManager.INSTALL_ALLOW_TEST;
                    break;
                case "-s":
                    sessionParams.installFlags |= PackageManager.INSTALL_EXTERNAL;
                    break;
                case "-f":
                    sessionParams.installFlags |= PackageManager.INSTALL_INTERNAL;
                    break;
                case "-d":
                    sessionParams.installFlags |= PackageManager.INSTALL_ALLOW_DOWNGRADE;
                    break;
                case "-g":
                    sessionParams.installFlags |= PackageManager.INSTALL_GRANT_RUNTIME_PERMISSIONS;
                    break;
                case "--dont-kill":
                    sessionParams.installFlags |= PackageManager.INSTALL_DONT_KILL_APP;
                    break;
                case "--originating-uri":
                    sessionParams.originatingUri = Uri.parse(getNextArg());
                    break;
                case "--referrer":
                    sessionParams.referrerUri = Uri.parse(getNextArg());
                    break;
                case "-p":
                    sessionParams.mode = SessionParams.MODE_INHERIT_EXISTING;
                    sessionParams.appPackageName = getNextArg();
                    if (sessionParams.appPackageName == null) {
                        throw new IllegalArgumentException("Missing inherit package name");
                    }
                    break;
                case "-S":
                    final long sizeBytes = Long.parseLong(getNextArg());
                    if (sizeBytes <= 0) {
                        throw new IllegalArgumentException("Size must be positive");
                    }
                    sessionParams.setSize(sizeBytes);
                    break;
                case "--abi":
                    sessionParams.abiOverride = checkAbiArgument(getNextArg());
                    break;
                case "--ephemeral":
                case "--instantapp":
                    sessionParams.setInstallAsInstantApp(true /*isInstantApp*/);
                    break;
                case "--full":
                    sessionParams.setInstallAsInstantApp(false /*isInstantApp*/);
                    break;
                case "--preload":
                    sessionParams.setInstallAsVirtualPreload();
                    break;
                case "--user":
                    params.userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--install-location":
                    sessionParams.installLocation = Integer.parseInt(getNextArg());
                    break;
                case "--force-uuid":
                    sessionParams.installFlags |= PackageManager.INSTALL_FORCE_VOLUME_UUID;
                    sessionParams.volumeUuid = getNextArg();
                    if ("internal".equals(sessionParams.volumeUuid)) {
                        sessionParams.volumeUuid = null;
                    }
                    break;
                case "--force-sdk":
                    sessionParams.installFlags |= PackageManager.INSTALL_FORCE_SDK;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option " + opt);
            }
        }
        return params;
    }

    private int runSetHomeActivity() {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        String component = getNextArg();
        ComponentName componentName =
                component != null ? ComponentName.unflattenFromString(component) : null;

        if (componentName == null) {
            pw.println("Error: component name not specified or invalid");
            return 1;
        }

        try {
            mInterface.setHomeActivity(componentName, userId);
            pw.println("Success");
            return 0;
        } catch (Exception e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runGetPrivappPermissions() {
        final String pkg = getNextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified.");
            return 1;
        }
        ArraySet<String> privAppPermissions = SystemConfig.getInstance().getPrivAppPermissions(pkg);
        getOutPrintWriter().println(privAppPermissions == null
                ? "{}" : privAppPermissions.toString());
        return 0;
    }

    private int runGetPrivappDenyPermissions() {
        final String pkg = getNextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified.");
            return 1;
        }
        ArraySet<String> privAppDenyPermissions =
                SystemConfig.getInstance().getPrivAppDenyPermissions(pkg);
        getOutPrintWriter().println(privAppDenyPermissions == null
                ? "{}" : privAppDenyPermissions.toString());
        return 0;
    }

    private int runGetInstantAppResolver() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            final ComponentName instantAppsResolver = mInterface.getInstantAppResolverComponent();
            if (instantAppsResolver == null) {
                return 1;
            }
            pw.println(instantAppsResolver.flattenToString());
            return 0;
        } catch (Exception e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runHasFeature() {
        final PrintWriter err = getErrPrintWriter();
        final String featureName = getNextArg();
        if (featureName == null) {
            err.println("Error: expected FEATURE name");
            return 1;
        }
        final String versionString = getNextArg();
        try {
            final int version = (versionString == null) ? 0 : Integer.parseInt(versionString);
            final boolean hasFeature = mInterface.hasSystemFeature(featureName, version);
            getOutPrintWriter().println(hasFeature);
            return hasFeature ? 0 : 1;
        } catch (NumberFormatException e) {
            err.println("Error: illegal version number " + versionString);
            return 1;
        } catch (RemoteException e) {
            err.println(e.toString());
            return 1;
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

    private int translateUserId(int userId, String logContext) {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, logContext, "pm command");
    }

    private int doCreateSession(SessionParams params, String installerPackageName, int userId)
            throws RemoteException {
        userId = translateUserId(userId, "runInstallCreate");
        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_SYSTEM;
            params.installFlags |= PackageManager.INSTALL_ALL_USERS;
        }

        final int sessionId = mInterface.getPackageInstaller()
                .createSession(params, installerPackageName, userId);
        return sessionId;
    }

    private int doWriteSplit(int sessionId, String inPath, long sizeBytes, String splitName,
            boolean logSuccess) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        if (FORCE_STREAM_INSTALL && inPath != null && !STDIN_PATH.equals(inPath)) {
            pw.println("Error: APK content must be streamed");
            return 1;
        }
        if (STDIN_PATH.equals(inPath)) {
            inPath = null;
        } else if (inPath != null) {
            final File file = new File(inPath);
            if (file.isFile()) {
                sizeBytes = file.length();
            }
        }
        if (sizeBytes <= 0) {
            pw.println("Error: must specify a APK size");
            return 1;
        }

        final SessionInfo info = mInterface.getPackageInstaller().getSessionInfo(sessionId);

        PackageInstaller.Session session = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));

            if (inPath != null) {
                in = new FileInputStream(inPath);
            } else {
                in = new SizedInputStream(getRawInputStream(), sizeBytes);
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

            if (logSuccess) {
                pw.println("Success: streamed " + total + " bytes");
            }
            return 0;
        } catch (IOException e) {
            pw.println("Error: failed to write; " + e.getMessage());
            return 1;
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(session);
        }
    }

    private int doRemoveSplit(int sessionId, String splitName, boolean logSuccess)
            throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            session.removeSplit(splitName);

            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } catch (IOException e) {
            pw.println("Error: failed to remove split; " + e.getMessage());
            return 1;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doCommitSession(int sessionId, boolean logSuccess) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));

            // Sanity check that all .dm files match an apk.
            // (The installer does not support standalone .dm files and will not process them.)
            try {
                DexMetadataHelper.validateDexPaths(session.getNames());
            } catch (IllegalStateException | IOException e) {
                pw.println("Warning [Could not validate the dex paths: " + e.getMessage() + "]");
            }

            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());

            final Intent result = receiver.getResult();
            final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                if (logSuccess) {
                    pw.println("Success");
                }
            } else {
                pw.println("Failure ["
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            }
            return status;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doAbandonSession(int sessionId, boolean logSuccess) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            session.abandon();
            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private void doListPermissions(ArrayList<String> groupList, boolean groups, boolean labels,
            boolean summary, int startProtectionLevel, int endProtectionLevel)
                    throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int groupCount = groupList.size();
        for (int i = 0; i < groupCount; i++) {
            String groupName = groupList.get(i);
            String prefix = "";
            if (groups) {
                if (i > 0) {
                    pw.println("");
                }
                if (groupName != null) {
                    PermissionGroupInfo pgi =
                            mInterface.getPermissionGroupInfo(groupName, 0 /*flags*/);
                    if (summary) {
                        Resources res = getResources(pgi);
                        if (res != null) {
                            pw.print(loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel) + ": ");
                        } else {
                            pw.print(pgi.name + ": ");

                        }
                    } else {
                        pw.println((labels ? "+ " : "") + "group:" + pgi.name);
                        if (labels) {
                            pw.println("  package:" + pgi.packageName);
                            Resources res = getResources(pgi);
                            if (res != null) {
                                pw.println("  label:"
                                        + loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel));
                                pw.println("  description:"
                                        + loadText(pgi, pgi.descriptionRes,
                                                pgi.nonLocalizedDescription));
                            }
                        }
                    }
                } else {
                    pw.println(((labels && !summary) ? "+ " : "") + "ungrouped:");
                }
                prefix = "  ";
            }
            List<PermissionInfo> ps =
                    mInterface.queryPermissionsByGroup(groupList.get(i), 0 /*flags*/).getList();
            final int count = ps.size();
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
                        pw.print(", ");
                    }
                    Resources res = getResources(pi);
                    if (res != null) {
                        pw.print(loadText(pi, pi.labelRes,
                                pi.nonLocalizedLabel));
                    } else {
                        pw.print(pi.name);
                    }
                } else {
                    pw.println(prefix + (labels ? "+ " : "")
                            + "permission:" + pi.name);
                    if (labels) {
                        pw.println(prefix + "  package:" + pi.packageName);
                        Resources res = getResources(pi);
                        if (res != null) {
                            pw.println(prefix + "  label:"
                                    + loadText(pi, pi.labelRes,
                                            pi.nonLocalizedLabel));
                            pw.println(prefix + "  description:"
                                    + loadText(pi, pi.descriptionRes,
                                            pi.nonLocalizedDescription));
                        }
                        pw.println(prefix + "  protectionLevel:"
                                + PermissionInfo.protectionToString(pi.protectionLevel));
                    }
                }
            }

            if (summary) {
                pw.println("");
            }
        }
    }

    private String loadText(PackageItemInfo pii, int res, CharSequence nonLocalized)
            throws RemoteException {
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

    private Resources getResources(PackageItemInfo pii) throws RemoteException {
        Resources res = mResourceCache.get(pii.packageName);
        if (res != null) return res;

        ApplicationInfo ai = mInterface.getApplicationInfo(pii.packageName, 0, 0);
        AssetManager am = new AssetManager();
        am.addAssetPath(ai.publicSourceDir);
        res = new Resources(am, null, null);
        mResourceCache.put(pii.packageName, res);
        return res;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Package manager (package) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  compile [-m MODE | -r REASON] [-f] [-c] [--split SPLIT_NAME]");
        pw.println("          [--reset] [--check-prof (true | false)] (-a | TARGET-PACKAGE)");
        pw.println("    Trigger compilation of TARGET-PACKAGE or all packages if \"-a\".");
        pw.println("    Options:");
        pw.println("      -a: compile all packages");
        pw.println("      -c: clear profile data before compiling");
        pw.println("      -f: force compilation even if not needed");
        pw.println("      -m: select compilation mode");
        pw.println("          MODE is one of the dex2oat compiler filters:");
        pw.println("            assume-verified");
        pw.println("            extract");
        pw.println("            verify");
        pw.println("            quicken");
        pw.println("            space-profile");
        pw.println("            space");
        pw.println("            speed-profile");
        pw.println("            speed");
        pw.println("            everything");
        pw.println("      -r: select compilation reason");
        pw.println("          REASON is one of:");
        for (int i = 0; i < PackageManagerServiceCompilerMapping.REASON_STRINGS.length; i++) {
            pw.println("            " + PackageManagerServiceCompilerMapping.REASON_STRINGS[i]);
        }
        pw.println("      --reset: restore package to its post-install state");
        pw.println("      --check-prof (true | false): look at profiles when doing dexopt?");
        pw.println("      --secondary-dex: compile app secondary dex files");
        pw.println("      --split SPLIT: compile only the given split name");
        pw.println("  bg-dexopt-job");
        pw.println("    Execute the background optimizations immediately.");
        pw.println("    Note that the command only runs the background optimizer logic. It may");
        pw.println("    overlap with the actual job but the job scheduler will not be able to");
        pw.println("    cancel it. It will also run even if the device is not in the idle");
        pw.println("    maintenance mode.");
        pw.println("  list features");
        pw.println("    Prints all features of the system.");
        pw.println("  list instrumentation [-f] [TARGET-PACKAGE]");
        pw.println("    Prints all test packages; optionally only those targeting TARGET-PACKAGE");
        pw.println("    Options:");
        pw.println("      -f: dump the name of the .apk file containing the test package");
        pw.println("  list libraries");
        pw.println("    Prints all system libraries.");
        pw.println("  list packages [-f] [-d] [-e] [-s] [-3] [-i] [-l] [-u] [-U] "
                + "[--uid UID] [--user USER_ID] [FILTER]");
        pw.println("    Prints all packages; optionally only those whose name contains");
        pw.println("    the text in FILTER.");
        pw.println("    Options:");
        pw.println("      -f: see their associated file");
        pw.println("      -d: filter to only show disabled packages");
        pw.println("      -e: filter to only show enabled packages");
        pw.println("      -s: filter to only show system packages");
        pw.println("      -3: filter to only show third party packages");
        pw.println("      -i: see the installer for the packages");
        pw.println("      -l: ignored (used for compatibility with older releases)");
        pw.println("      -U: also show the package UID");
        pw.println("      -u: also include uninstalled packages");
        pw.println("      --uid UID: filter to only show packages with the given UID");
        pw.println("      --user USER_ID: only list packages belonging to the given user");
        pw.println("  reconcile-secondary-dex-files TARGET-PACKAGE");
        pw.println("    Reconciles the package secondary dex files with the generated oat files.");
        pw.println("  list permission-groups");
        pw.println("    Prints all known permission groups.");
        pw.println("  list permissions [-g] [-f] [-d] [-u] [GROUP]");
        pw.println("    Prints all known permissions; optionally only those in GROUP.");
        pw.println("    Options:");
        pw.println("      -g: organize by group");
        pw.println("      -f: print all information");
        pw.println("      -s: short summary");
        pw.println("      -d: only list dangerous permissions");
        pw.println("      -u: list only the permissions users will see");
        pw.println("  dump-profiles TARGET-PACKAGE");
        pw.println("    Dumps method/class profile files to");
        pw.println("    /data/misc/profman/TARGET-PACKAGE.txt");
        pw.println("  resolve-activity [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints the activity that resolves to the given Intent.");
        pw.println("  query-activities [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints all activities that can handle the given Intent.");
        pw.println("  query-services [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints all services that can handle the given Intent.");
        pw.println("  query-receivers [--brief] [--components] [--user USER_ID] INTENT");
        pw.println("    Prints all broadcast receivers that can handle the given Intent.");
        pw.println("  suspend [--user USER_ID] TARGET-PACKAGE");
        pw.println("    Suspends the specified package (as user).");
        pw.println("  unsuspend [--user USER_ID] TARGET-PACKAGE");
        pw.println("    Unsuspends the specified package (as user).");
        pw.println("  set-home-activity [--user USER_ID] TARGET-COMPONENT");
        pw.println("    set the default home activity (aka launcher).");
        pw.println("  has-feature FEATURE_NAME [version]");
        pw.println("   prints true and returns exit status 0 when system has a FEATURE_NAME,");
        pw.println("   otherwise prints false and returns exit status 1");
        pw.println();
        Intent.printIntentArgsHelp(pw , "");
    }

    private static class LocalIntentReceiver {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
            @Override
            public void send(int code, Intent intent, String resolvedType, IBinder whitelistToken,
                    IIntentReceiver finishedReceiver, String requiredPermission, Bundle options) {
                try {
                    mResult.offer(intent, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
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
}
