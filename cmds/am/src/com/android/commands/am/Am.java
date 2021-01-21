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

package com.android.commands.am;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.content.pm.IPackageManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.util.AndroidException;

import com.android.internal.os.BaseCommand;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;

public class Am extends BaseCommand {

    private IActivityManager mAm;
    private IPackageManager mPm;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Am()).run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        try {
            runAmCmd(new String[] { "help" });
        } catch (AndroidException e) {
            e.printStackTrace(System.err);
        }
    }

    @Override
    public void onRun() throws Exception {

        mAm = ActivityManager.getService();
        if (mAm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to activity manager; is the system running?");
        }

        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to package manager; is the system running?");
        }

        String op = nextArgRequired();

        if (op.equals("instrument")) {
            runInstrument();
        } else {
            runAmCmd(getRawArgs());
        }
    }

    int parseUserArg(String arg) {
        int userId;
        if ("all".equals(arg)) {
            userId = UserHandle.USER_ALL;
        } else if ("current".equals(arg) || "cur".equals(arg)) {
            userId = UserHandle.USER_CURRENT;
        } else {
            userId = Integer.parseInt(arg);
        }
        return userId;
    }

    static final class MyShellCallback extends ShellCallback {
        boolean mActive = true;

        @Override public ParcelFileDescriptor onOpenFile(String path, String seLinuxContext,
                String mode) {
            if (!mActive) {
                System.err.println("Open attempt after active for: " + path);
                return null;
            }
            File file = new File(path);
            //System.err.println("Opening file: " + file.getAbsolutePath());
            //Log.i("Am", "Opening file: " + file.getAbsolutePath());
            final ParcelFileDescriptor fd;
            try {
                fd = ParcelFileDescriptor.open(file,
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE |
                        ParcelFileDescriptor.MODE_WRITE_ONLY);
            } catch (FileNotFoundException e) {
                String msg = "Unable to open file " + path + ": " + e;
                System.err.println(msg);
                throw new IllegalArgumentException(msg);
            }
            if (seLinuxContext != null) {
                final String tcon = SELinux.getFileContext(file.getAbsolutePath());
                if (!SELinux.checkSELinuxAccess(seLinuxContext, tcon, "file", "write")) {
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                    String msg = "System server has no access to file context " + tcon;
                    System.err.println(msg + " (from path " + file.getAbsolutePath()
                            + ", context " + seLinuxContext + ")");
                    throw new IllegalArgumentException(msg);
                }
            }
            return fd;
        }
    }

    void runAmCmd(String[] args) throws AndroidException {
        final MyShellCallback cb = new MyShellCallback();
        try {
            mAm.asBinder().shellCommand(FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                    args, cb, new ResultReceiver(null) { });
        } catch (RemoteException e) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't call activity manager; is the system running?");
        } finally {
            cb.mActive = false;
        }
    }

    public void runInstrument() throws Exception {
        Instrument instrument = new Instrument(mAm, mPm);

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-p")) {
                instrument.profileFile = nextArgRequired();
            } else if (opt.equals("-w")) {
                instrument.wait = true;
            } else if (opt.equals("-r")) {
                instrument.rawMode = true;
            } else if (opt.equals("-m")) {
                instrument.protoStd = true;
            } else if (opt.equals("-f")) {
                instrument.protoFile = true;
                if (peekNextArg() != null && !peekNextArg().startsWith("-"))
                    instrument.logPath = nextArg();
            } else if (opt.equals("-e")) {
                final String argKey = nextArgRequired();
                final String argValue = nextArgRequired();
                instrument.args.putString(argKey, argValue);
            } else if (opt.equals("--no_window_animation")
                    || opt.equals("--no-window-animation")) {
                instrument.noWindowAnimation = true;
            } else if (opt.equals("--no-hidden-api-checks")) {
                instrument.disableHiddenApiChecks = true;
            } else if (opt.equals("--no-test-api-access")) {
                instrument.disableTestApiChecks = false;
            } else if (opt.equals("--no-isolated-storage")) {
                instrument.disableIsolatedStorage = true;
            } else if (opt.equals("--user")) {
                instrument.userId = parseUserArg(nextArgRequired());
            } else if (opt.equals("--abi")) {
                instrument.abi = nextArgRequired();
            } else if (opt.equals("--no-restart")) {
                instrument.noRestart = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        if (instrument.userId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't start instrumentation with user 'all'");
            return;
        }

        instrument.componentNameArg = nextArgRequired();
        instrument.run();
    }
}
