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

package com.android.commands.bu;

import android.app.backup.IBackupManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.OsConstants;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

public final class Backup {
    static final String TAG = "bu";

    static String[] mArgs;
    int mNextArg;
    IBackupManager mBackupManager;

    public static void main(String[] args) {
        Log.d(TAG, "Beginning: " + args[0]);
        mArgs = args;
        try {
            new Backup().run();
        } catch (Exception e) {
            Log.e(TAG, "Error running backup/restore", e);
        }
        Log.d(TAG, "Finished.");
    }

    public void run() {
        mBackupManager = IBackupManager.Stub.asInterface(ServiceManager.getService("backup"));
        if (mBackupManager == null) {
            Log.e(TAG, "Can't obtain Backup Manager binder");
            return;
        }

        String arg = nextArg();
        if (arg.equals("backup")) {
            doBackup(OsConstants.STDOUT_FILENO);
        } else if (arg.equals("restore")) {
            doRestore(OsConstants.STDIN_FILENO);
        } else {
            showUsage();
        }
    }

    private void doBackup(int socketFd) {
        ArrayList<String> packages = new ArrayList<String>();
        boolean saveApks = false;
        boolean saveObbs = false;
        boolean saveShared = false;
        boolean doEverything = false;
        boolean doWidgets = false;
        boolean allIncludesSystem = true;
        boolean doCompress = true;
        boolean doKeyValue = false;

        String arg;
        while ((arg = nextArg()) != null) {
            if (arg.startsWith("-")) {
                if ("-apk".equals(arg)) {
                    saveApks = true;
                } else if ("-noapk".equals(arg)) {
                    saveApks = false;
                } else if ("-obb".equals(arg)) {
                    saveObbs = true;
                } else if ("-noobb".equals(arg)) {
                    saveObbs = false;
                } else if ("-shared".equals(arg)) {
                    saveShared = true;
                } else if ("-noshared".equals(arg)) {
                    saveShared = false;
                } else if ("-system".equals(arg)) {
                    allIncludesSystem = true;
                } else if ("-nosystem".equals(arg)) {
                    allIncludesSystem = false;
                } else if ("-widgets".equals(arg)) {
                    doWidgets = true;
                } else if ("-nowidgets".equals(arg)) {
                    doWidgets = false;
                } else if ("-all".equals(arg)) {
                    doEverything = true;
                } else if ("-compress".equals(arg)) {
                    doCompress = true;
                } else if ("-nocompress".equals(arg)) {
                    doCompress = false;
                } else if ("-keyvalue".equals(arg)) {
                    doKeyValue = true;
                } else if ("-nokeyvalue".equals(arg)) {
                    doKeyValue = false;
                } else {
                    Log.w(TAG, "Unknown backup flag " + arg);
                    continue;
                }
            } else {
                // Not a flag; treat as a package name
                packages.add(arg);
            }
        }

        if (doEverything && packages.size() > 0) {
            Log.w(TAG, "-all passed for backup along with specific package names");
        }

        if (!doEverything && !saveShared && packages.size() == 0) {
            Log.e(TAG, "no backup packages supplied and neither -shared nor -all given");
            return;
        }

        ParcelFileDescriptor fd = null;
        try {
            fd = ParcelFileDescriptor.adoptFd(socketFd);
            String[] packArray = new String[packages.size()];
            mBackupManager.adbBackup(fd, saveApks, saveObbs, saveShared, doWidgets, doEverything,
                    allIncludesSystem, doCompress, doKeyValue, packages.toArray(packArray));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to invoke backup manager for backup");
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e) {
                    Log.e(TAG, "IO error closing output for backup: " + e.getMessage());
                }
            }
        }
    }

    private void doRestore(int socketFd) {
        // No arguments to restore
        ParcelFileDescriptor fd = null;
        try {
            fd = ParcelFileDescriptor.adoptFd(socketFd);
            mBackupManager.adbRestore(fd);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to invoke backup manager for restore");
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e) {}
            }
        }
    }

    private static void showUsage() {
        System.err.println(" backup [-f FILE] [-apk|-noapk] [-obb|-noobb] [-shared|-noshared] [-all]");
        System.err.println("        [-system|-nosystem] [-keyvalue|-nokeyvalue] [PACKAGE...]");
        System.err.println("     write an archive of the device's data to FILE [default=backup.adb]");
        System.err.println("     package list optional if -all/-shared are supplied");
        System.err.println("     -apk/-noapk: do/don't back up .apk files (default -noapk)");
        System.err.println("     -obb/-noobb: do/don't back up .obb files (default -noobb)");
        System.err.println("     -shared|-noshared: do/don't back up shared storage (default -noshared)");
        System.err.println("     -all: back up all installed applications");
        System.err.println("     -system|-nosystem: include system apps in -all (default -system)");
        System.err.println("     -keyvalue|-nokeyvalue: include apps that perform key/value backups.");
        System.err.println("         (default -nokeyvalue)");
        System.err.println(" restore FILE             restore device contents from FILE");
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }
}