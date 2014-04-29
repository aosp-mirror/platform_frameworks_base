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

import android.app.backup.RestoreSet;
import android.app.backup.IBackupManager;
import android.app.backup.IRestoreObserver;
import android.app.backup.IRestoreSession;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.HashSet;

public final class Bmgr {
    IBackupManager mBmgr;
    IRestoreSession mRestore;

    static final String BMGR_NOT_RUNNING_ERR =
            "Error: Could not access the Backup Manager.  Is the system running?";
    static final String TRANSPORT_NOT_RUNNING_ERR =
        "Error: Could not access the backup transport.  Is the system running?";

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

        if (arg.indexOf('.') >= 0) {
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
        System.err.println("to the 'transport' and 'wipe' commands.  The currently selected transport");
        System.err.println("is indicated with a '*' character.");
        System.err.println("");
        System.err.println("The 'list sets' command reports the token and name of each restore set");
        System.err.println("available to the device via the current transport.");
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
    }
}
