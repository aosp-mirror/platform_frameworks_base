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
        new Bmgr().run(args);
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

    private void doList() {
        String arg = nextArg();     // sets, transports, packages set#
        if ("transports".equals(arg)) {
            doListTransports();
            return;
        }

        // The rest of the 'list' options work with a restore session on the current transport
        try {
            int curTransport = mBmgr.getCurrentTransport();
            mRestore = mBmgr.beginRestoreSession(curTransport);

            if ("sets".equals(arg)) {
                doListRestoreSets();
            }

            mRestore.endRestoreSession();
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(BMGR_NOT_RUNNING_ERR);
        }
    }

    private void doListTransports() {
        
    }

    private void doListRestoreSets() {
        try {
            RestoreSet[] sets = mRestore.getAvailableRestoreSets();
            if (sets.length == 0) {
                System.out.println("No restore sets available");
            } else {
                for (RestoreSet s : sets) {
                    System.out.println("  " + s.token + " : " + s.name);
                }
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(TRANSPORT_NOT_RUNNING_ERR);
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
        System.err.println("       bmgr backup [-f] package");
        System.err.println("       bmgr list sets");
        System.err.println("       #bmgr list transports");
        System.err.println("       #bmgr transport which#");
        System.err.println("       #bmgr restore set#");
        System.err.println("       bmgr run");
    }
}