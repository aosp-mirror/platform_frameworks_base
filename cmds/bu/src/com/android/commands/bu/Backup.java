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

        int socketFd = Integer.parseInt(nextArg());

        String arg = nextArg();
        if (arg.equals("backup")) {
            doFullBackup(socketFd);
        } else if (arg.equals("restore")) {
            doFullRestore(socketFd);
        } else {
            Log.e(TAG, "Invalid operation '" + arg + "'");
        }
    }

    private void doFullBackup(int socketFd) {
        ArrayList<String> packages = new ArrayList<String>();
        boolean saveApks = false;
        boolean saveObbs = false;
        boolean saveShared = false;
        boolean doEverything = false;
        boolean allIncludesSystem = true;

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
                } else if ("-all".equals(arg)) {
                    doEverything = true;
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
            mBackupManager.fullBackup(fd, saveApks, saveObbs, saveShared, doEverything,
                    allIncludesSystem, packages.toArray(packArray));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to invoke backup manager for backup");
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e) {}
            }
        }
    }

    private void doFullRestore(int socketFd) {
        // No arguments to restore
        ParcelFileDescriptor fd = null;
        try {
            fd = ParcelFileDescriptor.adoptFd(socketFd);
            mBackupManager.fullRestore(fd);
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

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }
}