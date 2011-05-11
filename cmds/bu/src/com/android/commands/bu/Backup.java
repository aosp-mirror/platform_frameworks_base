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

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;

public final class Backup {
    static final String TAG = "Backup";

    private static String[] mArgs;
    private int mNextArg;

    public static void main(String[] args) {
        mArgs = args;
        try {
            new Backup().run();
        } catch (Exception e) {
            Log.e(TAG, "Error running backup", e);
        }
        Log.d(TAG, "Finished.");
    }

    public void run() {
        IBackupManager bmgr = IBackupManager.Stub.asInterface(ServiceManager.getService("backup"));
        if (bmgr == null) {
            System.err.println("ERROR: could not contact backup manager");
            return;
        }

        ArrayList<String> packages = new ArrayList<String>();
        boolean saveApks = false;
        boolean saveShared = false;
        boolean doEverything = false;

        String arg;
        while ((arg = nextArg()) != null) {
            
            if (arg.startsWith("-")) {
                if ("-apk".equals(arg)) {
                    saveApks = true;
                } else if ("-noapk".equals(arg)) {
                    saveApks = false;
                } else if ("-shared".equals(arg)) {
                    saveShared = true;
                } else if ("-noshared".equals(arg)) {
                    saveShared = false;
                } else if ("-all".equals(arg)) {
                    doEverything = true;
                } else {
                    System.err.println("ERROR: unknown flag " + arg);
                    return;
                }
            } else {
                // Not a flag; treat as a package name
                packages.add(arg);
            }
        }

        if (doEverything && packages.size() > 0) {
            System.err.println("ERROR: -all used with specific package set");
            return;
        }

        if (!doEverything && packages.size() == 0) {
            System.err.println("ERROR: no packages supplied and -all not used");
            return;
        }

        try {
            ParcelFileDescriptor fd = ParcelFileDescriptor.dup(FileDescriptor.out);
            String[] packArray = new String[packages.size()];
            bmgr.fullBackup(fd, saveApks, saveShared, doEverything, packages.toArray(packArray));
        } catch (IOException e) {
            System.err.println("ERROR: cannot dup System.out");
        } catch (RemoteException e) {
            System.err.println("ERROR: unable to invoke backup manager service");
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