/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.commands.bmgr;

import android.app.backup.BackupManager;
import android.app.backup.BackupProgress;
import android.app.backup.RestoreSet;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupObserver;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class Bmgr {
    IBackupManager mBmgr;
    IRestoreSession mRestore;

    static final String BMGR_NOT_RUNNING_ERR =
            "Error: Could not access the Backup Manager.  Is the system running?";
    static final String TRANSPORT_NOT_RUNNING_ERR =
            "Error: Could not access the backup transport.  Is the system running?";
    static final String PM_NOT_RUNNING_ERR =
            "Error: Could not access the Package Manager.  Is the system running?";

    private String[] mArgs;
    private int mNextArg;

    public static void main(String[] args) {
        try {
            new Bmgr().run(args);
        } catch (Exception e) {
            System.err.println("Exception caught:");
            e.printStackTrace();
        }
    }

    public void run(String[] args) {
        if (args.length < 1) {
            showUsage();
            return;
        }

        mBmgr = IBackupManager.Stub.asInterface(ServiceManager.getService("backup"));
        if (mBmgr == null) {
            System.err.println(BMGR_NOT_RUNNING_ERR);
            return;
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if ("enabled".equals(op)) {
            doEnabled();
            return;
        }

        if ("enable".equals(op)) {
            doEnable();
            return;
        }

        if ("run".equals(op)) {
            doRun();
            return;
        }

        if ("backup".equals(op)) {
            doBackup();
            return;
        }

        if ("list".equals(op)) {
            doList();
            return;
        }

        if ("restore".equals(op)) {
            doRestore();
            return;
        }

        if ("transport".equals(op)) {
            doTransport();
            return;
        }

        if ("wipe".equals(op)) {
            doWipe();
            return;
        }

        if ("fullbackup".equals(op)) {
            doFullTransportBackup();
            return;
        }

        if ("backupnow".equals(op)) {
            doBackupNow();
            return;
        }

        if ("whitelist".equals(op)) {
            doPrintWhitelist();
            return;
        }

        System.err.println("Unknown command");
        showUsage();
    }

    private String enableToString(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private void doEnabled() {
        try {
            boolean isEnabled = mBmgr.isBackupEnabled();
            System.out.println("Backup Manager currently "
                    + enableToString(isEnabled));
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doEnable() {
        String arg = nextArg();
        if (arg == null) {
            showUsage();
            return;
        }

        try {
            boolean enable = Boolean.parseBoolean(arg);
            mBmgr.setBackupEnabled(enable);
            System.out.println("Backup Manager now " + enableToString(enable));
        } catch (NumberFormatException e) {
            showUsage();
            return;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doRun() {
        try {
            mBmgr.backupNow();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doBackup() {
        String pkg = nextArg();
        if (pkg == null) {
            showUsage();
            return;
        }

        try {
            mBmgr.dataChanged(pkg);
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doFullTransportBackup() {
        System.out.println("Performing full transport backup");

        String pkg;
        ArrayList<String> allPkgs = new ArrayList<String>();
        while ((pkg = nextArg()) != null) {
            allPkgs.add(pkg);
        }
        if (allPkgs.size() > 0) {
            try {
                mBmgr.fullTransportBackup(allPkgs.toArray(new String[allPkgs.size()]));
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(BMGR_NOT_RUNNING_ERR);
            }
        }
    }

    class BackupObserver extends IBackupObserver.Stub {
        boolean done = false;

        @Override
        public void onUpdate(String currentPackage, BackupProgress backupProgress) {
            System.out.println(
                "Package " + currentPackage + " with progress: " + backupProgress.bytesTransferred
                    + "/" + backupProgress.bytesExpected);
        }

        @Override
        public void onResult(String currentPackage, int status) {
            System.out.println("Package " + currentPackage + " with result: "
                    + convertBackupStatusToString(status));
        }

        @Override
        public void backupFinished(int status) {
            System.out.println("Backup finished with result: "
                    + convertBackupStatusToString(status));
            synchronized (this) {
                done = true;
                this.notify();
            }
        }

        public void waitForCompletion() {
            // The backupFinished() callback will throw the 'done' flag; we
            // just sit and wait on that notification.
            synchronized (this) {
                while (!this.done) {
                    try {
                        this.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }

    }

    private static String convertBackupStatusToString(int errorCode) {
        switch (errorCode) {
            case BackupManager.SUCCESS:
                return "Success";
            case BackupManager.ERROR_BACKUP_NOT_ALLOWED:
                return "Backup is not allowed";
            case BackupManager.ERROR_PACKAGE_NOT_FOUND:
                return "Package not found";
            case BackupManager.ERROR_TRANSPORT_ABORTED:
                return "Transport error";
            case BackupManager.ERROR_TRANSPORT_PACKAGE_REJECTED:
                return "Transport rejected package";
            case BackupManager.ERROR_AGENT_FAILURE:
                return "Agent error";
            case BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED:
                return "Size quota exceeded";
            default:
                return "Unknown error";
        }
    }

    private void backupNowAllPackages() {
        int userId = UserHandle.USER_SYSTEM;
        IPackageManager mPm =
                IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            System.err.println(PM_NOT_RUNNING_ERR);
            return;
        }
        List<PackageInfo> installedPackages = null;
        try {
            installedPackages =  mPm.getInstalledPackages(0, userId).getList();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
        if (installedPackages != null) {
            List<String> packages = new ArrayList<>();
            for (PackageInfo pi : installedPackages) {
                try {
                    if (mBmgr.isAppEligibleForBackup(pi.packageName)) {
                        packages.add(pi.packageName);
                    }
                } catch (RemoteException e) {
                    System.err.println(e.toString());
                    System.err.println(BMGR_NOT_RUNNING_ERR);
                }
            }
            backupNowPackages(packages);
        }
    }

    private void backupNowPackages(List<String> packages) {
        try {
            BackupObserver observer = new BackupObserver();
            int err = mBmgr.requestBackup(packages.toArray(new String[packages.size()]), observer);
            if (err == 0) {
                // Off and running -- wait for the backup to complete
                observer.waitForCompletion();
            } else {
                System.err.println("Unable to run backup");
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doBackupNow() {
        String pkg;
        boolean backupAll = false;
        ArrayList<String> allPkgs = new ArrayList<String>();
        while ((pkg = nextArg()) != null) {
            if (pkg.equals("--all")) {
                backupAll = true;
            } else {
                allPkgs.add(pkg);
            }
        }
        if (backupAll) {
            if (allPkgs.size() == 0) {
                System.out.println("Running backup for all packages.");
                backupNowAllPackages();
            } else {
                System.err.println("Provide only '--all' flag or list of packages.");
            }
        } else if (allPkgs.size() > 0) {
            System.out.println("Running backup for " + allPkgs.size() +" requested packages.");
            backupNowPackages(allPkgs);
        } else {
            System.err.println("Provide '--all' flag or list of packages.");
        }
    }

    private void doTransport() {
        try {
            String which = nextArg();
            if (which == null) {
                showUsage();
                return;
            }

            String old = mBmgr.selectBackupTransport(which);
            if (old == null) {
                System.out.println("Unknown transport '" + which
                        + "' specified; no changes made.");
            } else {
                System.out.println("Selected transport " + which + " (formerly " + old + ")");
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doWipe() {
        String transport = nextArg();
        if (transport == null) {
            showUsage();
            return;
        }

        String pkg = nextArg();
        if (pkg == null) {
            showUsage();
            return;
        }

        try {
            mBmgr.clearBackupData(transport, pkg);
            System.out.println("Wiped backup data for " + pkg + " on " + transport);
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doList() {
        String arg = nextArg();     // sets, transports, packages set#
        if ("transports".equals(arg)) {
            doListTransports();
            return;
        }

        // The rest of the 'list' options work with a restore session on the current transport
        try {
            mRestore = mBmgr.beginRestoreSession(null, null);
            if (mRestore == null) {
                System.err.println(BMGR_NOT_RUNNING_ERR);
                return;
            }

            if ("sets".equals(arg)) {
                doListRestoreSets();
            } else if ("transports".equals(arg)) {
                doListTransports();
            }

            mRestore.endRestoreSession();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doListTransports() {
        try {
            String current = mBmgr.getCurrentTransport();
            String[] transports = mBmgr.listAllTransports();
            if (transports == null || transports.length == 0) {
                System.out.println("No transports available.");
                return;
            }

            for (String t : transports) {
                String pad = (t.equals(current)) ? "  * " : "    ";
                System.out.println(pad + t);
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doListRestoreSets() {
        try {
            RestoreObserver observer = new RestoreObserver();
            int err = mRestore.getAvailableRestoreSets(observer);
            if (err != 0) {
                System.out.println("Unable to request restore sets");
            } else {
                observer.waitForCompletion();
                printRestoreSets(observer.sets);
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(TRANSPORT_NOT_RUNNING_ERR);
        }
    }

    private void printRestoreSets(RestoreSet[] sets) {
        if (sets == null || sets.length == 0) {
            System.out.println("No restore sets");
            return;
        }
        for (RestoreSet s : sets) {
            System.out.println("  " + Long.toHexString(s.token) + " : " + s.name);
        }
    }

    class RestoreObserver extends IRestoreObserver.Stub {
        boolean done;
        RestoreSet[] sets = null;

        public void restoreSetsAvailable(RestoreSet[] result) {
            synchronized (this) {
                sets = result;
                done = true;
                this.notify();
            }
        }

        public void restoreStarting(int numPackages) {
            System.out.println("restoreStarting: " + numPackages + " packages");
        }

        public void onUpdate(int nowBeingRestored, String currentPackage) {
            System.out.println("onUpdate: " + nowBeingRestored + " = " + currentPackage);
        }

        public void restoreFinished(int error) {
            System.out.println("restoreFinished: " + error);
            synchronized (this) {
                done = true;
                this.notify();
            }
        }

        public void waitForCompletion() {
            // The restoreFinished() callback will throw the 'done' flag; we
            // just sit and wait on that notification.
            synchronized (this) {
                while (!this.done) {
                    try {
                        this.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }
    }

    private void doRestore() {
        String arg = nextArg();
        if (arg == null) {
            showUsage();
            return;
        }

        if (arg.indexOf('.') >= 0 || arg.equals("android")) {
            // it's a package name
            doRestorePackage(arg);
        } else {
            try {
                long token = Long.parseLong(arg, 16);
                HashSet<String> filter = null;
                while ((arg = nextArg()) != null) {
                    if (filter == null) filter = new HashSet<String>();
                    filter.add(arg);
                }

                doRestoreAll(token, filter);
            } catch (NumberFormatException e) {
                showUsage();
                return;
            }
        }

        System.out.println("done");
    }

    private void doRestorePackage(String pkg) {
        try {
            mRestore = mBmgr.beginRestoreSession(pkg, null);
            if (mRestore == null) {
                System.err.println(BMGR_NOT_RUNNING_ERR);
                return;
            }

            RestoreObserver observer = new RestoreObserver();
            int err = mRestore.restorePackage(pkg, observer);
            if (err == 0) {
                // Off and running -- wait for the restore to complete
                observer.waitForCompletion();
            } else {
                System.err.println("Unable to restore package " + pkg);
            }

            // And finally shut down the session
            mRestore.endRestoreSession();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doRestoreAll(long token, HashSet<String> filter) {
        RestoreObserver observer = new RestoreObserver();

        try {
            boolean didRestore = false;
            mRestore = mBmgr.beginRestoreSession(null, null);
            if (mRestore == null) {
                System.err.println(BMGR_NOT_RUNNING_ERR);
                return;
            }
            RestoreSet[] sets = null;
            int err = mRestore.getAvailableRestoreSets(observer);
            if (err == 0) {
                observer.waitForCompletion();
                sets = observer.sets;
                if (sets != null) {
                    for (RestoreSet s : sets) {
                        if (s.token == token) {
                            System.out.println("Scheduling restore: " + s.name);
                            if (filter == null) {
                                didRestore = (mRestore.restoreAll(token, observer) == 0);
                            } else {
                                String[] names = new String[filter.size()];
                                filter.toArray(names);
                                didRestore = (mRestore.restoreSome(token, observer, names) == 0);
                            }
                            break;
                        }
                    }
                }
            }
            if (!didRestore) {
                if (sets == null || sets.length == 0) {
                    System.out.println("No available restore sets; no restore performed");
                } else {
                    System.out.println("No matching restore set token.  Available sets:");
                    printRestoreSets(sets);
                }
            }

            // if we kicked off a restore successfully, we have to wait for it
            // to complete before we can shut down the restore session safely
            if (didRestore) {
                observer.waitForCompletion();
            }

            // once the restore has finished, close down the session and we're done
            mRestore.endRestoreSession();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doPrintWhitelist() {
        try {
            final String[] whitelist = mBmgr.getTransportWhitelist();
            if (whitelist != null) {
                for (String transport : whitelist) {
                    System.out.println(transport);
                }
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
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
        System.err.println("usage: bmgr [backup|restore|list|transport|run]");
        System.err.println("       bmgr backup PACKAGE");
        System.err.println("       bmgr enable BOOL");
        System.err.println("       bmgr enabled");
        System.err.println("       bmgr list transports");
        System.err.println("       bmgr list sets");
        System.err.println("       bmgr transport WHICH");
        System.err.println("       bmgr restore TOKEN");
        System.err.println("       bmgr restore TOKEN PACKAGE...");
        System.err.println("       bmgr restore PACKAGE");
        System.err.println("       bmgr run");
        System.err.println("       bmgr wipe TRANSPORT PACKAGE");
        System.err.println("       bmgr fullbackup PACKAGE...");
        System.err.println("       bmgr backupnow --all|PACKAGE...");
        System.err.println("");
        System.err.println("The 'backup' command schedules a backup pass for the named package.");
        System.err.println("Note that the backup pass will effectively be a no-op if the package");
        System.err.println("does not actually have changed data to store.");
        System.err.println("");
        System.err.println("The 'enable' command enables or disables the entire backup mechanism.");
        System.err.println("If the argument is 'true' it will be enabled, otherwise it will be");
        System.err.println("disabled.  When disabled, neither backup or restore operations will");
        System.err.println("be performed.");
        System.err.println("");
        System.err.println("The 'enabled' command reports the current enabled/disabled state of");
        System.err.println("the backup mechanism.");
        System.err.println("");
        System.err.println("The 'list transports' command reports the names of the backup transports");
        System.err.println("currently available on the device.  These names can be passed as arguments");
        System.err.println("to the 'transport' and 'wipe' commands.  The currently active transport");
        System.err.println("is indicated with a '*' character.");
        System.err.println("");
        System.err.println("The 'list sets' command reports the token and name of each restore set");
        System.err.println("available to the device via the currently active transport.");
        System.err.println("");
        System.err.println("The 'transport' command designates the named transport as the currently");
        System.err.println("active one.  This setting is persistent across reboots.");
        System.err.println("");
        System.err.println("The 'restore' command when given just a restore token initiates a full-system");
        System.err.println("restore operation from the currently active transport.  It will deliver");
        System.err.println("the restore set designated by the TOKEN argument to each application");
        System.err.println("that had contributed data to that restore set.");
        System.err.println("");
        System.err.println("The 'restore' command when given a token and one or more package names");
        System.err.println("initiates a restore operation of just those given packages from the restore");
        System.err.println("set designated by the TOKEN argument.  It is effectively the same as the");
        System.err.println("'restore' operation supplying only a token, but applies a filter to the");
        System.err.println("set of applications to be restored.");
        System.err.println("");
        System.err.println("The 'restore' command when given just a package name intiates a restore of");
        System.err.println("just that one package according to the restore set selection algorithm");
        System.err.println("used by the RestoreSession.restorePackage() method.");
        System.err.println("");
        System.err.println("The 'run' command causes any scheduled backup operation to be initiated");
        System.err.println("immediately, without the usual waiting period for batching together");
        System.err.println("data changes.");
        System.err.println("");
        System.err.println("The 'wipe' command causes all backed-up data for the given package to be");
        System.err.println("erased from the given transport's storage.  The next backup operation");
        System.err.println("that the given application performs will rewrite its entire data set.");
        System.err.println("Transport names to use here are those reported by 'list transports'.");
        System.err.println("");
        System.err.println("The 'fullbackup' command induces a full-data stream backup for one or more");
        System.err.println("packages.  The data is sent via the currently active transport.");
        System.err.println("");
        System.err.println("The 'backupnow' command runs an immediate backup for one or more packages.");
        System.err.println("    --all flag runs backup for all eligible packages.");
        System.err.println("For each package it will run key/value or full data backup ");
        System.err.println("depending on the package's manifest declarations.");
        System.err.println("The data is sent via the currently active transport.");
    }
}
