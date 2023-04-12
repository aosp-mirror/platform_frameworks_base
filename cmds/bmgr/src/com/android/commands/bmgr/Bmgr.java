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

import android.annotation.IntDef;
import android.annotation.UserIdInt;
import android.app.backup.BackupManager;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.BackupProgress;
import android.app.backup.BackupRestoreEventLogger;
import android.app.backup.BackupTransport;
import android.app.backup.IBackupManager;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IBackupObserver;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.app.backup.ISelectBackupTransportCallback;
import android.app.backup.RestoreSet;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Adb shell command for {@link android.app.backup.IBackupManager}.
 */
public class Bmgr {
    public static final String TAG = "Bmgr";

    private final IBackupManager mBmgr;
    private IRestoreSession mRestore;

    private static final String BMGR_NOT_RUNNING_ERR =
            "Error: Could not access the Backup Manager.  Is the system running?";
    private static final String BMGR_NOT_ACTIVATED_FOR_USER =
            "Error: Backup Manager is not activated for user ";
    private static final String BMGR_ERR_NO_RESTORESESSION_FOR_USER =
            "Error: Could not get restore session for user ";
    private static final String TRANSPORT_NOT_RUNNING_ERR =
            "Error: Could not access the backup transport.  Is the system running?";
    private static final String PM_NOT_RUNNING_ERR =
            "Error: Could not access the Package Manager.  Is the system running?";

    private String[] mArgs;
    private int mNextArg;

    @VisibleForTesting
    Bmgr(IBackupManager bmgr) {
        mBmgr = bmgr;
    }

    Bmgr() {
        mBmgr = IBackupManager.Stub.asInterface(ServiceManager.getService(Context.BACKUP_SERVICE));
    }

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

        mArgs = args;
        mNextArg = 0;
        int userId = parseUserId();
        String op = nextArg();
        Slog.v(TAG, "Running " + op + " for user:" + userId);

        if (mBmgr == null) {
            System.err.println(BMGR_NOT_RUNNING_ERR);
            return;
        }

        if ("activate".equals(op)) {
            doActivateService(userId);
            return;
        }

        if ("activated".equals(op)) {
            doActivated(userId);
            return;
        }

        if (!isBackupActive(userId)) {
            return;
        }

        if ("autorestore".equals(op)) {
            doAutoRestore(userId);
            return;
        }

        if ("enabled".equals(op)) {
            doEnabled(userId);
            return;
        }

        if ("enable".equals(op)) {
            doEnable(userId);
            return;
        }

        if ("run".equals(op)) {
            doRun(userId);
            return;
        }

        if ("backup".equals(op)) {
            doBackup(userId);
            return;
        }

        if ("init".equals(op)) {
            doInit(userId);
            return;
        }

        if ("list".equals(op)) {
            doList(userId);
            return;
        }

        if ("restore".equals(op)) {
            doRestore(userId);
            return;
        }

        if ("transport".equals(op)) {
            doTransport(userId);
            return;
        }

        if ("wipe".equals(op)) {
            doWipe(userId);
            return;
        }

        if ("fullbackup".equals(op)) {
            doFullTransportBackup(userId);
            return;
        }

        if ("backupnow".equals(op)) {
            doBackupNow(userId);
            return;
        }

        if ("cancel".equals(op)) {
            doCancel(userId);
            return;
        }

        if ("whitelist".equals(op)) {
            doPrintWhitelist();
            return;
        }

        if ("scheduling".equals(op)) {
            setSchedulingEnabled(userId);
            return;
        }

        System.err.println("Unknown command");
        showUsage();
    }

    private void setSchedulingEnabled(int userId) {
        String arg = nextArg();
        if (arg == null) {
            showUsage();
            return;
        }

        try {
            boolean enable = Boolean.parseBoolean(arg);
            mBmgr.setFrameworkSchedulingEnabledForUser(userId, enable);
            System.out.println(
                    "Backup scheduling is now "
                            + (enable ? "enabled" : "disabled")
                            + " for user "
                            + userId);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void handleRemoteException(RemoteException e) {
        System.err.println(e.toString());
        System.err.println(BMGR_NOT_RUNNING_ERR);
    }

    private boolean isBackupActive(@UserIdInt int userId) {
        try {
            if (!mBmgr.isBackupServiceActive(userId)) {
                System.err.println(BMGR_NOT_ACTIVATED_FOR_USER + userId);
                return false;
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
            return false;
        }

        return true;
    }

    private void doAutoRestore(int userId) {
        String arg = nextArg();
        if (arg == null) {
            showUsage();
            return;
        }

        try {
            boolean enable = Boolean.parseBoolean(arg);
            mBmgr.setAutoRestore(enable);
            System.out.println(
                    "Auto restore is now "
                            + (enable ? "enabled" : "disabled")
                            + " for user "
                            + userId);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private String activatedToString(boolean activated) {
        return activated ? "activated" : "deactivated";
    }

    private void doActivated(@UserIdInt int userId) {
        try {
            System.out.println("Backup Manager currently "
                    + activatedToString(mBmgr.isBackupServiceActive(userId)));
        } catch (RemoteException e) {
            handleRemoteException(e);
        }

    }

    private String enableToString(boolean enabled) {
        return enabled ? "enabled" : "disabled";
    }

    private void doEnabled(@UserIdInt int userId) {
        try {
            boolean isEnabled = mBmgr.isBackupEnabledForUser(userId);
            System.out.println("Backup Manager currently "
                    + enableToString(isEnabled));
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doEnable(@UserIdInt int userId) {
        String arg = nextArg();
        if (arg == null) {
            showUsage();
            return;
        }

        try {
            boolean enable = Boolean.parseBoolean(arg);
            mBmgr.setBackupEnabledForUser(userId, enable);
            System.out.println("Backup Manager now " + enableToString(enable));
        } catch (NumberFormatException e) {
            showUsage();
            return;
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    void doRun(@UserIdInt int userId) {
        try {
            mBmgr.backupNowForUser(userId);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doBackup(@UserIdInt int userId) {
        String pkg = nextArg();
        if (pkg == null) {
            showUsage();
            return;
        }

        try {
            mBmgr.dataChangedForUser(userId, pkg);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doFullTransportBackup(@UserIdInt int userId) {
        System.out.println("Performing full transport backup");

        String pkg;
        ArraySet<String> allPkgs = new ArraySet<String>();
        while ((pkg = nextArg()) != null) {
            allPkgs.add(pkg);
        }
        if (allPkgs.size() > 0) {
            try {
                mBmgr.fullTransportBackupForUser(
                        userId, allPkgs.toArray(new String[allPkgs.size()]));
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        }
    }

    // IBackupObserver generically usable for any backup/init operation
    private static abstract class Observer extends IBackupObserver.Stub {
        private final Object trigger = new Object();

        @GuardedBy("trigger")
        private volatile boolean done = false;

        @Override
        public void onUpdate(String currentPackage, BackupProgress backupProgress) {
        }

        @Override
        public void onResult(String currentPackage, int status) {
        }

        @Override
        public void backupFinished(int status) {
            synchronized (trigger) {
                done = true;
                trigger.notify();
            }
        }

        public boolean done() {
            return this.done;
        }

        // Wait forever
        public void waitForCompletion() {
            waitForCompletion(0);
        }

        // Wait for a given time and then give up
        public void waitForCompletion(long timeout) {
            // The backupFinished() callback will throw the 'done' flag; we
            // just sit and wait on that notification.
            final long targetTime = SystemClock.elapsedRealtime() + timeout;
            synchronized (trigger) {
                // Wait until either we're done, or we've reached a stated positive timeout
                while (!done && (timeout <= 0 || SystemClock.elapsedRealtime() < targetTime)) {
                    try {
                        trigger.wait(1000L);
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }
    }

    private static class BackupObserver extends Observer {
        @Override
        public void onUpdate(String currentPackage, BackupProgress backupProgress) {
            super.onUpdate(currentPackage, backupProgress);
            System.out.println(
                "Package " + currentPackage + " with progress: " + backupProgress.bytesTransferred
                    + "/" + backupProgress.bytesExpected);
        }

        @Override
        public void onResult(String currentPackage, int status) {
            super.onResult(currentPackage, status);
            System.out.println("Package " + currentPackage + " with result: "
                    + convertBackupStatusToString(status));
        }

        @Override
        public void backupFinished(int status) {
            super.backupFinished(status);
            System.out.println("Backup finished with result: "
                    + convertBackupStatusToString(status));
            if (status == BackupManager.ERROR_BACKUP_CANCELLED) {
                System.out.println("Backups can be cancelled if a backup is already running, check "
                                + "backup dumpsys");
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
                return "Transport rejected package because it wasn't able to process it"
                        + " at the time";
            case BackupManager.ERROR_AGENT_FAILURE:
                return "Agent error";
            case BackupManager.ERROR_TRANSPORT_QUOTA_EXCEEDED:
                return "Size quota exceeded";
            case BackupManager.ERROR_BACKUP_CANCELLED:
                return "Backup cancelled";
            default:
                return "Unknown error";
        }
    }

    private void backupNowAllPackages(@UserIdInt int userId, boolean nonIncrementalBackup,
            @Monitor int monitorState) {
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
            String[] packages =
                    installedPackages.stream().map(p -> p.packageName).toArray(String[]::new);
            String[] filteredPackages = {};
            try {
                filteredPackages = mBmgr.filterAppsEligibleForBackupForUser(userId, packages);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
            backupNowPackages(userId, Arrays.asList(filteredPackages), nonIncrementalBackup,
                    monitorState);
        }
    }

    private void backupNowPackages(
            @UserIdInt int userId,
            List<String> packages, boolean nonIncrementalBackup, @Monitor int monitorState) {
        int flags = 0;
        if (nonIncrementalBackup) {
            flags |= BackupManager.FLAG_NON_INCREMENTAL_BACKUP;
        }
        try {
            BackupObserver observer = new BackupObserver();
            BackupMonitor monitor =
                    (monitorState != Monitor.OFF)
                            ? new BackupMonitor(monitorState == Monitor.VERBOSE)
                            : null;
            int err = mBmgr.requestBackupForUser(
                    userId,
                    packages.toArray(new String[packages.size()]),
                    observer,
                    monitor,
                    flags);
            if (err == 0) {
                // Off and running -- wait for the backup to complete
                observer.waitForCompletion();
            } else {
                System.err.println("Unable to run backup");
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doBackupNow(@UserIdInt int userId) {
        String pkg;
        boolean backupAll = false;
        boolean nonIncrementalBackup = false;
        @Monitor int monitor = Monitor.OFF;
        ArrayList<String> allPkgs = new ArrayList<String>();
        while ((pkg = nextArg()) != null) {
            if (pkg.equals("--all")) {
                backupAll = true;
            } else if (pkg.equals("--non-incremental")) {
                nonIncrementalBackup = true;
            } else if (pkg.equals("--incremental")) {
                nonIncrementalBackup = false;
            } else if (pkg.equals("--monitor")) {
                monitor = Monitor.NORMAL;
            } else if (pkg.equals("--monitor-verbose")) {
                monitor = Monitor.VERBOSE;
            } else {
                if (!allPkgs.contains(pkg)) {
                    allPkgs.add(pkg);
                }
            }
        }
        if (backupAll) {
            if (allPkgs.size() == 0) {
                System.out.println("Running " + (nonIncrementalBackup ? "non-" : "") +
                        "incremental backup for all packages.");
                backupNowAllPackages(userId, nonIncrementalBackup, monitor);
            } else {
                System.err.println("Provide only '--all' flag or list of packages.");
            }
        } else if (allPkgs.size() > 0) {
            System.out.println("Running " + (nonIncrementalBackup ? "non-" : "") +
                    "incremental backup for " + allPkgs.size() +" requested packages.");
            backupNowPackages(userId, allPkgs, nonIncrementalBackup, monitor);
        } else {
            System.err.println("Provide '--all' flag or list of packages.");
        }
    }

    private void doCancel(@UserIdInt int userId) {
        String arg = nextArg();
        if ("backups".equals(arg)) {
            try {
                mBmgr.cancelBackupsForUser(userId);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
            return;
        }

        System.err.println("Unknown command.");
    }

    private void doTransport(@UserIdInt int userId) {
        try {
            String which = nextArg();
            if (which == null) {
                showUsage();
                return;
            }

            if ("-c".equals(which)) {
                doTransportByComponent(userId);
                return;
            }

            String old = mBmgr.selectBackupTransportForUser(userId, which);
            if (old == null) {
                System.out.println("Unknown transport '" + which
                        + "' specified; no changes made.");
            } else {
                System.out.println("Selected transport " + which + " (formerly " + old + ")");
            }

        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doTransportByComponent(@UserIdInt int userId) {
        String which = nextArg();
        if (which == null) {
            showUsage();
            return;
        }

        final CountDownLatch latch = new CountDownLatch(1);

        try {
            mBmgr.selectBackupTransportAsyncForUser(
                    userId,
                    ComponentName.unflattenFromString(which),
                    new ISelectBackupTransportCallback.Stub() {
                        @Override
                        public void onSuccess(String transportName) {
                            System.out.println("Success. Selected transport: " + transportName);
                            latch.countDown();
                        }

                        @Override
                        public void onFailure(int reason) {
                            System.err.println("Failure. error=" + reason);
                            latch.countDown();
                        }
                    });
        } catch (RemoteException e) {
            handleRemoteException(e);
            return;
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            System.err.println("Operation interrupted.");
        }
    }

    private void doWipe(@UserIdInt int userId) {
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
            mBmgr.clearBackupDataForUser(userId, transport, pkg);
            System.out.println("Wiped backup data for " + pkg + " on " + transport);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    class InitObserver extends Observer {
        public int result = BackupTransport.TRANSPORT_ERROR;

        @Override
        public void backupFinished(int status) {
            super.backupFinished(status);
            result = status;
        }
    }

    private void doInit(@UserIdInt int userId) {
        ArraySet<String> transports = new ArraySet<>();
        String transport;
        while ((transport = nextArg()) != null) {
            transports.add(transport);
        }
        if (transports.size() == 0) {
            showUsage();
            return;
        }

        InitObserver observer = new InitObserver();
        try {
            System.out.println("Initializing transports: " + transports);
            mBmgr.initializeTransportsForUser(
                    userId, transports.toArray(new String[transports.size()]), observer);
            observer.waitForCompletion(30*1000L);
            System.out.println("Initialization result: " + observer.result);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doList(@UserIdInt int userId) {
        String arg = nextArg();     // sets, transports, packages set#
        if ("transports".equals(arg)) {
            doListTransports(userId);
            return;
        }

        // The rest of the 'list' options work with a restore session on the current transport
        try {
            mRestore = mBmgr.beginRestoreSessionForUser(userId, null, null);
            if (mRestore == null) {
                System.err.println(BMGR_ERR_NO_RESTORESESSION_FOR_USER + userId);
                return;
            }

            if ("sets".equals(arg)) {
                doListRestoreSets();
            }

            mRestore.endRestoreSession();
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doListTransports(@UserIdInt int userId) {
        String arg = nextArg();

        try {
            if ("-c".equals(arg)) {
                for (ComponentName transport : mBmgr.listAllTransportComponentsForUser(userId)) {
                    System.out.println(transport.flattenToShortString());
                }
                return;
            }

            String current = mBmgr.getCurrentTransportForUser(userId);
            String[] transports = mBmgr.listAllTransportsForUser(userId);
            if (transports == null || transports.length == 0) {
                System.out.println("No transports available.");
                return;
            }

            for (String t : transports) {
                String pad = (t.equals(current)) ? "  * " : "    ";
                System.out.println(pad + t);
            }
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    private void doListRestoreSets() {
        try {
            RestoreObserver observer = new RestoreObserver();
            // TODO implement monitor here
            int err = mRestore.getAvailableRestoreSets(observer, null);
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

        /**
         * Wait until either {@link #restoreFinished} or {@link #restoreStarting} is called.
         * Once one is called, it clears the internal flag again, so that the same observer intance
         * can be reused for a next operation.
         */
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
                done = false;
            }
        }
    }

    private void doRestore(@UserIdInt int userId) {
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
                @Monitor int monitor = Monitor.OFF;

                long token = Long.parseLong(arg, 16);
                HashSet<String> filter = null;
                while ((arg = nextArg()) != null) {
                    if (arg.equals("--monitor")) {
                        monitor = Monitor.NORMAL;
                    } else if (arg.equals("--monitor-verbose")) {
                        monitor = Monitor.VERBOSE;
                    } else {
                        if (filter == null) filter = new HashSet<String>();
                        filter.add(arg);
                    }
                }

                doRestoreAll(userId, token, filter, monitor);
            } catch (NumberFormatException e) {
                showUsage();
                return;
            }
        }
    }

    private void doRestorePackage(String pkg) {
        System.err.println("The syntax 'restore <package>' is no longer supported, please use ");
        System.err.println("'restore <token> <package>'.");
    }

    private void doRestoreAll(@UserIdInt int userId, long token, HashSet<String> filter,
            @Monitor int monitorState) {
        RestoreObserver observer = new RestoreObserver();

        try {
            boolean didRestore = false;
            mRestore = mBmgr.beginRestoreSessionForUser(userId, null, null);
            if (mRestore == null) {
                System.err.println(BMGR_ERR_NO_RESTORESESSION_FOR_USER + userId);
                return;
            }
            RestoreSet[] sets = null;
            BackupMonitor monitor =
                    (monitorState != Monitor.OFF)
                            ? new BackupMonitor(monitorState == Monitor.VERBOSE)
                            : null;
            int err = mRestore.getAvailableRestoreSets(observer, monitor);
            if (err == 0) {
                observer.waitForCompletion();
                sets = observer.sets;
                if (sets != null) {
                    for (RestoreSet s : sets) {
                        if (s.token == token) {
                            System.out.println("Scheduling restore: " + s.name);
                            if (filter == null) {
                                didRestore = (mRestore.restoreAll(token, observer, monitor) == 0);
                            } else {
                                String[] names = new String[filter.size()];
                                filter.toArray(names);
                                didRestore = (mRestore.restorePackages(token, observer, names,
                                        monitor) == 0);
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

            System.out.println("done");
        } catch (RemoteException e) {
            handleRemoteException(e);
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
            handleRemoteException(e);
        }
    }

    private void doActivateService(int userId) {
        String arg = nextArg();
        if (arg == null) {
            showUsage();
            return;
        }

        try {
            boolean activate = Boolean.parseBoolean(arg);
            mBmgr.setBackupServiceActive(userId, activate);
            System.out.println(
                    "Backup service now "
                            + (activate ? "activated" : "deactivated")
                            + " for user "
                            + userId);
        } catch (RemoteException e) {
            handleRemoteException(e);
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

    private int parseUserId() {
        String arg = nextArg();
        if ("--user".equals(arg)) {
            return UserHandle.parseUserArg(nextArg());
        } else {
            mNextArg--;
            return UserHandle.USER_SYSTEM;
        }
    }

    private static void showUsage() {
        System.err.println("usage: bmgr [--user <userId>] [backup|restore|list|transport|run]");
        System.err.println("       bmgr backup PACKAGE");
        System.err.println("       bmgr enable BOOL");
        System.err.println("       bmgr enabled");
        System.err.println("       bmgr list transports [-c]");
        System.err.println("       bmgr list sets");
        System.err.println("       bmgr transport WHICH|-c WHICH_COMPONENT");
        System.err.println("       bmgr restore TOKEN [--monitor|--monitor-verbose]");
        System.err.println("       bmgr restore TOKEN PACKAGE... [--monitor|--monitor-verbose]");
        System.err.println("       bmgr run");
        System.err.println("       bmgr wipe TRANSPORT PACKAGE");
        System.err.println("       bmgr fullbackup PACKAGE...");
        System.err.println("       bmgr backupnow [--monitor|--monitor-verbose] --all|PACKAGE...");
        System.err.println("       bmgr cancel backups");
        System.err.println("       bmgr init TRANSPORT...");
        System.err.println("       bmgr activate BOOL");
        System.err.println("       bmgr activated");
        System.err.println("       bmgr autorestore BOOL");
        System.err.println("       bmgr scheduling BOOL");
        System.err.println("");
        System.err.println("The '--user' option specifies the user on which the operation is run.");
        System.err.println("It must be the first argument before the operation.");
        System.err.println("The default value is 0 which is the system user.");
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
        System.err.println("BackupManager is currently bound to. These names can be passed as arguments");
        System.err.println("to the 'transport' and 'wipe' commands.  The currently active transport");
        System.err.println("is indicated with a '*' character. If -c flag is used, all available");
        System.err.println("transport components on the device are listed. These can be used with");
        System.err.println("the component variant of 'transport' command.");
        System.err.println("");
        System.err.println("The 'list sets' command reports the token and name of each restore set");
        System.err.println("available to the device via the currently active transport.");
        System.err.println("");
        System.err.println("The 'transport' command designates the named transport as the currently");
        System.err.println("active one.  This setting is persistent across reboots. If -c flag is");
        System.err.println("specified, the following string is treated as a component name.");
        System.err.println("");
        System.err.println("The 'restore' command when given just a restore token initiates a full-system");
        System.err.println("restore operation from the currently active transport.  It will deliver");
        System.err.println("the restore set designated by the TOKEN argument to each application");
        System.err.println("that had contributed data to that restore set.");
        System.err.println("    --monitor flag prints monitor events (important events and errors");
        System.err.println("              encountered during restore).");
        System.err.println("    --monitor-verbose flag prints monitor events with all keys.");
        System.err.println("");
        System.err.println("The 'restore' command when given a token and one or more package names");
        System.err.println("initiates a restore operation of just those given packages from the restore");
        System.err.println("set designated by the TOKEN argument.  It is effectively the same as the");
        System.err.println("'restore' operation supplying only a token, but applies a filter to the");
        System.err.println("set of applications to be restored.");
        System.err.println("    --monitor flag prints monitor events (important events and errors");
        System.err.println("              encountered during restore).");
        System.err.println("    --monitor-verbose flag prints monitor events with all keys.");
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
        System.err.println("    --monitor flag prints monitor events (important events and errors");
        System.err.println("              encountered during backup).");
        System.err.println("    --monitor-verbose flag prints monitor events with all keys.");
        System.err.println("For each package it will run key/value or full data backup ");
        System.err.println("depending on the package's manifest declarations.");
        System.err.println("The data is sent via the currently active transport.");
        System.err.println("");
        System.err.println("The 'cancel backups' command cancels all running backups.");
        System.err.println("");
        System.err.println("The 'init' command initializes the given transports, wiping all data");
        System.err.println("from their backing data stores.");
        System.err.println("");
        System.err.println("The 'activate' command activates or deactivates the backup service.");
        System.err.println("If the argument is 'true' it will be activated, otherwise it will be");
        System.err.println("deactivated. When deactivated, the service will not be running and no");
        System.err.println("operations can be performed until activation.");
        System.err.println("");
        System.err.println("The 'activated' command reports the current activated/deactivated");
        System.err.println("state of the backup mechanism.");
        System.err.println("");
        System.err.println("The 'autorestore' command enables or disables automatic restore when");
        System.err.println("a new package is installed.");
        System.err.println("");
        System.err.println("The 'scheduling' command enables or disables backup scheduling in the");
        System.err.println("framework.");
    }

    private static class BackupMonitor extends IBackupManagerMonitor.Stub {
        private final boolean mVerbose;

        private BackupMonitor(boolean verbose) {
            mVerbose = verbose;
        }

        @Override
        public void onEvent(Bundle event) throws RemoteException {
            StringBuilder out = new StringBuilder();
            int id = event.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_ID);
            int category = event.getInt(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY);
            out.append("=> Event{").append(eventCategoryToString(category));
            out.append(" / ").append(eventIdToString(id));
            String packageName = event.getString(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME);
            if (packageName != null) {
                out.append(" : package = ").append(packageName);
                if (event.containsKey(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION)) {
                    long version =
                            event.getLong(
                                    BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION);
                    out.append("(v").append(version).append(")");
                }
            }
            if (event.containsKey(BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS)) {
                ArrayList<BackupRestoreEventLogger.DataTypeResult> results =
                        event.getParcelableArrayList(
                                BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS,
                                BackupRestoreEventLogger.DataTypeResult.class);
                out.append(", results = [");
                for (BackupRestoreEventLogger.DataTypeResult result : results) {
                    out.append("\n{\n\tdataType: ");
                    out.append(result.getDataType());
                    out.append("\n\tsuccessCount: ");
                    out.append(result.getSuccessCount());
                    out.append("\n\tfailCount: ");
                    out.append(result.getFailCount());
                    out.append("\n\tmetadataHash: ");
                    out.append(Arrays.toString(result.getMetadataHash()));

                    if (!result.getErrors().isEmpty()) {
                        out.append("\n\terrors: [");
                        for (String error : result.getErrors().keySet()) {
                            out.append(error);
                            out.append(": ");
                            out.append(result.getErrors().get(error));
                            out.append(";");
                        }
                        out.append("]");
                    }
                    out.append("\n}");

                }
                out.append("]");
            }
            if (mVerbose) {
                Set<String> remainingKeys = new ArraySet<>(event.keySet());
                remainingKeys.remove(BackupManagerMonitor.EXTRA_LOG_EVENT_ID);
                remainingKeys.remove(BackupManagerMonitor.EXTRA_LOG_EVENT_CATEGORY);
                remainingKeys.remove(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME);
                remainingKeys.remove(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_LONG_VERSION);
                remainingKeys.remove(BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_VERSION);
                remainingKeys.remove(BackupManagerMonitor.EXTRA_LOG_AGENT_LOGGING_RESULTS);
                if (!remainingKeys.isEmpty()) {
                    out.append(", other keys =");
                    for (String key : remainingKeys) {
                        out.append(" ").append(key);
                    }
                }
            }
            out.append("}");
            System.out.println(out.toString());
        }
    }

    private static String eventCategoryToString(int eventCategory) {
        switch (eventCategory) {
            case BackupManagerMonitor.LOG_EVENT_CATEGORY_TRANSPORT:
                return "TRANSPORT";
            case BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT:
                return "AGENT";
            case BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY:
                return "BACKUP_MANAGER_POLICY";
            default:
                return "UNKNOWN_CATEGORY";
        }
    }

    private static String eventIdToString(int eventId) {
        switch (eventId) {
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_BACKUP_CANCEL:
                return "FULL_BACKUP_CANCEL";
            case BackupManagerMonitor.LOG_EVENT_ID_ILLEGAL_KEY:
                return "ILLEGAL_KEY";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_DATA_TO_SEND:
                return "NO_DATA_TO_SEND";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_INELIGIBLE:
                return "PACKAGE_INELIGIBLE";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_KEY_VALUE_PARTICIPANT:
                return "PACKAGE_KEY_VALUE_PARTICIPANT";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_STOPPED:
                return "PACKAGE_STOPPED";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_NOT_FOUND:
                return "PACKAGE_NOT_FOUND";
            case BackupManagerMonitor.LOG_EVENT_ID_BACKUP_DISABLED:
                return "BACKUP_DISABLED";
            case BackupManagerMonitor.LOG_EVENT_ID_DEVICE_NOT_PROVISIONED:
                return "DEVICE_NOT_PROVISIONED";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_TRANSPORT_NOT_PRESENT:
                return "PACKAGE_TRANSPORT_NOT_PRESENT";
            case BackupManagerMonitor.LOG_EVENT_ID_ERROR_PREFLIGHT:
                return "ERROR_PREFLIGHT";
            case BackupManagerMonitor.LOG_EVENT_ID_QUOTA_HIT_PREFLIGHT:
                return "QUOTA_HIT_PREFLIGHT";
            case BackupManagerMonitor.LOG_EVENT_ID_EXCEPTION_FULL_BACKUP:
                return "EXCEPTION_FULL_BACKUP";
            case BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_BACKUP_CANCEL:
                return "KEY_VALUE_BACKUP_CANCEL";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_RESTORE_METADATA_AVAILABLE:
                return "NO_RESTORE_METADATA_AVAILABLE";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_PM_METADATA_RECEIVED:
                return "NO_PM_METADATA_RECEIVED";
            case BackupManagerMonitor.LOG_EVENT_ID_PM_AGENT_HAS_NO_METADATA:
                return "PM_AGENT_HAS_NO_METADATA";
            case BackupManagerMonitor.LOG_EVENT_ID_LOST_TRANSPORT:
                return "LOST_TRANSPORT";
            case BackupManagerMonitor.LOG_EVENT_ID_PACKAGE_NOT_PRESENT:
                return "PACKAGE_NOT_PRESENT";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_VERSION_HIGHER:
                return "RESTORE_VERSION_HIGHER";
            case BackupManagerMonitor.LOG_EVENT_ID_APP_HAS_NO_AGENT:
                return "APP_HAS_NO_AGENT";
            case BackupManagerMonitor.LOG_EVENT_ID_SIGNATURE_MISMATCH:
                return "SIGNATURE_MISMATCH";
            case BackupManagerMonitor.LOG_EVENT_ID_CANT_FIND_AGENT:
                return "CANT_FIND_AGENT";
            case BackupManagerMonitor.LOG_EVENT_ID_KEY_VALUE_RESTORE_TIMEOUT:
                return "KEY_VALUE_RESTORE_TIMEOUT";
            case BackupManagerMonitor.LOG_EVENT_ID_RESTORE_ANY_VERSION:
                return "RESTORE_ANY_VERSION";
            case BackupManagerMonitor.LOG_EVENT_ID_VERSIONS_MATCH:
                return "VERSIONS_MATCH";
            case BackupManagerMonitor.LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER:
                return "VERSION_OF_BACKUP_OLDER";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH:
                return "FULL_RESTORE_SIGNATURE_MISMATCH";
            case BackupManagerMonitor.LOG_EVENT_ID_SYSTEM_APP_NO_AGENT:
                return "SYSTEM_APP_NO_AGENT";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE:
                return "FULL_RESTORE_ALLOW_BACKUP_FALSE";
            case BackupManagerMonitor.LOG_EVENT_ID_APK_NOT_INSTALLED:
                return "APK_NOT_INSTALLED";
            case BackupManagerMonitor.LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK:
                return "CANNOT_RESTORE_WITHOUT_APK";
            case BackupManagerMonitor.LOG_EVENT_ID_MISSING_SIGNATURE:
                return "MISSING_SIGNATURE";
            case BackupManagerMonitor.LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE:
                return "EXPECTED_DIFFERENT_PACKAGE";
            case BackupManagerMonitor.LOG_EVENT_ID_UNKNOWN_VERSION:
                return "UNKNOWN_VERSION";
            case BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_TIMEOUT:
                return "FULL_RESTORE_TIMEOUT";
            case BackupManagerMonitor.LOG_EVENT_ID_CORRUPT_MANIFEST:
                return "CORRUPT_MANIFEST";
            case BackupManagerMonitor.LOG_EVENT_ID_WIDGET_METADATA_MISMATCH:
                return "WIDGET_METADATA_MISMATCH";
            case BackupManagerMonitor.LOG_EVENT_ID_WIDGET_UNKNOWN_VERSION:
                return "WIDGET_UNKNOWN_VERSION";
            case BackupManagerMonitor.LOG_EVENT_ID_NO_PACKAGES:
                return "NO_PACKAGES";
            case BackupManagerMonitor.LOG_EVENT_ID_TRANSPORT_IS_NULL:
                return "TRANSPORT_IS_NULL";
            case BackupManagerMonitor.LOG_EVENT_ID_AGENT_LOGGING_RESULTS:
                return "AGENT_LOGGING_RESULTS";
            default:
                return "UNKNOWN_ID";
        }
    }

    @IntDef({Monitor.OFF, Monitor.NORMAL, Monitor.VERBOSE})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Monitor {
        int OFF = 0;
        int NORMAL = 1;
        int VERBOSE = 2;
    }
}
