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

import android.backup.IBackupManager;
import android.backup.IRestoreObserver;
import android.backup.IRestoreSession;
import android.backup.RestoreSet;
import android.os.RemoteException;
import android.os.ServiceManager;

public final class Bmgr {
    IBackupManager mBmgr;
    IRestoreSession mRestore;

    static final String BMGR_NOT_RUNNING_ERR =
            "Error: Could not access the Backup Manager.  Is the system running?";
    static final String TRANSPORT_NOT_RUNNING_ERR =
        "Error: Could not access the backup transport.  Is the system running?";

    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    public static void main(String[] args) {
        try {
            new Bmgr().run(args);
        } catch (Exception e) {
            System.err.println("Exception caught:");
            e.printStackTrace();
        }
    }

    public void run(String[] args) {
        boolean validCommand = false;
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
        boolean isFull = false;
        String pkg = nextArg();
        if ("-f".equals(pkg)) {
            isFull = true;
            pkg = nextArg();
        }

        if (pkg == null || pkg.startsWith("-")) {
            showUsage();
            return;
        }

        try {
            // !!! TODO: handle full backup
            mBmgr.dataChanged(pkg);
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doTransport() {
        try {
            String which = nextArg();
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
        String pkg = nextArg();
        if (pkg == null) {
            showUsage();
            return;
        }

        try {
            mBmgr.clearBackupData(pkg);
            System.out.println("Wiped backup data for " + pkg);
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
            String curTransport = mBmgr.getCurrentTransport();
            mRestore = mBmgr.beginRestoreSession(curTransport);
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
            RestoreSet[] sets = mRestore.getAvailableRestoreSets();
            if (sets == null || sets.length == 0) {
                System.out.println("No restore sets available");
            } else {
                printRestoreSets(sets);
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(TRANSPORT_NOT_RUNNING_ERR);
        }
    }

    private void printRestoreSets(RestoreSet[] sets) {
        for (RestoreSet s : sets) {
            System.out.println("  " + s.token + " : " + s.name);
        }
    }

    class RestoreObserver extends IRestoreObserver.Stub {
        boolean done;
        public void restoreStarting(int numPackages) {
            System.out.println("restoreStarting: " + numPackages + " packages");
        }

        public void onUpdate(int nowBeingRestored) {
            System.out.println("onUpdate: " + nowBeingRestored);
        }

        public void restoreFinished(int error) {
            System.out.println("restoreFinished: " + error);
            synchronized (this) {
                done = true;
                this.notify();
            }
        }
    }

    private void doRestore() {
        long token;
        try {
            token = Long.parseLong(nextArg());
        } catch (NumberFormatException e) {
            showUsage();
            return;
        }

        RestoreObserver observer = new RestoreObserver();

        try {
            boolean didRestore = false;
            String curTransport = mBmgr.getCurrentTransport();
            mRestore = mBmgr.beginRestoreSession(curTransport);
            if (mRestore == null) {
                System.err.println(BMGR_NOT_RUNNING_ERR);
                return;
            }
            RestoreSet[] sets = mRestore.getAvailableRestoreSets();
            for (RestoreSet s : sets) {
                if (s.token == token) {
                    System.out.println("Scheduling restore: " + s.name);
                    mRestore.performRestore(token, observer);
                    didRestore = true;
                    break;
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
            mRestore.endRestoreSession();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }

        // now wait for it to be done
        synchronized (observer) {
            while (!observer.done) {
                try {
                    observer.wait();
                } catch (InterruptedException ex) {
                }
            }
        }
        System.out.println("done");
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
        System.err.println("       bmgr run");
        System.err.println("       bmgr wipe PACKAGE");
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
        System.err.println("to the 'transport' command.  The currently selected transport is indicated");
        System.err.println("with a '*' character.");
        System.err.println("");
        System.err.println("The 'list sets' command reports the token and name of each restore set");
        System.err.println("available to the device via the current transport.");
        System.err.println("");
        System.err.println("The 'transport' command designates the named transport as the currently");
        System.err.println("active one.  This setting is persistent across reboots.");
        System.err.println("");
        System.err.println("The 'restore' command initiates a restore operation, using the restore set");
        System.err.println("from the current transport whose token matches the argument.");
        System.err.println("");
        System.err.println("The 'run' command causes any scheduled backup operation to be initiated");
        System.err.println("immediately, without the usual waiting period for batching together");
        System.err.println("data changes.");
        System.err.println("");
        System.err.println("The 'wipe' command causes all backed-up data for the given package to be");
        System.err.println("erased from the current transport's storage.  The next backup operation");
        System.err.println("that the given application performs will rewrite its entire data set.");
    }
}
