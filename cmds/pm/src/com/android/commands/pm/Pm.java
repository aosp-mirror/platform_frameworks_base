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

package com.android.commands.pm;

import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;
import static android.content.pm.PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;

import android.accounts.IAccountManager;
import android.app.ActivityManager;
import android.app.PackageInstallObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.ApkLite;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;

import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.SizedInputStream;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public final class Pm {
    private static final String TAG = "Pm";
    private static final String STDIN_PATH = "-";

    IPackageManager mPm;
    IPackageInstaller mInstaller;
    IUserManager mUm;
    IAccountManager mAm;

    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private static final String PM_NOT_RUNNING_ERR =
        "Error: Could not access the Package Manager.  Is the system running?";

    public static void main(String[] args) {
        int exitCode = 1;
        try {
            exitCode = new Pm().run(args);
        } catch (Exception e) {
            Log.e(TAG, "Error", e);
            System.err.println("Error: " + e);
            if (e instanceof RemoteException) {
                System.err.println(PM_NOT_RUNNING_ERR);
            }
        }
        System.exit(exitCode);
    }

    public int run(String[] args) throws RemoteException {
        if (args.length < 1) {
            return runShellCommand("package", mArgs);
        }
        mAm = IAccountManager.Stub.asInterface(ServiceManager.getService(Context.ACCOUNT_SERVICE));
        mUm = IUserManager.Stub.asInterface(ServiceManager.getService(Context.USER_SERVICE));
        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));

        if (mPm == null) {
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
        mInstaller = mPm.getPackageInstaller();

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if ("install".equals(op)) {
            return runInstall();
        }

        if ("install-create".equals(op)) {
            return runInstallCreate();
        }

        if ("install-write".equals(op)) {
            return runInstallWrite();
        }

        if ("install-commit".equals(op)) {
            return runInstallCommit();
        }

        if ("install-abandon".equals(op) || "install-destroy".equals(op)) {
            return runInstallAbandon();
        }

        return runShellCommand("package", mArgs);
    }

    static final class MyShellCallback extends ShellCallback {
        @Override public ParcelFileDescriptor onOpenFile(String path, String seLinuxContext,
                String mode) {
            File file = new File(path);
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

    private int runShellCommand(String serviceName, String[] args) {
        final HandlerThread handlerThread = new HandlerThread("results");
        handlerThread.start();
        try {
            ServiceManager.getService(serviceName).shellCommand(
                    FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                    args, new MyShellCallback(),
                    new ResultReceiver(new Handler(handlerThread.getLooper())));
            return 0;
        } catch (RemoteException e) {
            e.printStackTrace();
        } finally {
            handlerThread.quitSafely();
        }
        return -1;
    }

    private static class LocalIntentReceiver {
        private final SynchronousQueue<Intent> mResult = new SynchronousQueue<>();

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

    private int translateUserId(int userId, String logContext) {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, logContext, "pm command");
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

    /*
     * Keep this around to support existing users of the "pm install" command that may not be
     * able to be updated [or, at least informed the API has changed] such as ddmlib.
     *
     * Moving the implementation of "pm install" to "cmd package install" changes the executing
     * context. Instead of being a stand alone process, "cmd package install" runs in the
     * system_server process. Due to SELinux rules, system_server cannot access many directories;
     * one of which being the package install staging directory [/data/local/tmp].
     *
     * The use of "adb install" or "cmd package install" over "pm install" is highly encouraged.
     */
    private int runInstall() throws RemoteException {
        long startedTime = SystemClock.elapsedRealtime();
        final InstallParams params = makeInstallParams();
        final String inPath = nextArg();
        if (params.sessionParams.sizeBytes == -1 && !STDIN_PATH.equals(inPath)) {
            File file = new File(inPath);
            if (file.isFile()) {
                try {
                    ApkLite baseApk = PackageParser.parseApkLite(file, 0);
                    PackageLite pkgLite = new PackageLite(null, baseApk, null, null, null, null,
                            null, null);
                    params.sessionParams.setSize(
                            PackageHelper.calculateInstalledSize(pkgLite,
                            params.sessionParams.abiOverride));
                } catch (PackageParserException | IOException e) {
                    System.err.println("Error: Failed to parse APK file: " + e);
                    return 1;
                }
            } else {
                System.err.println("Error: Can't open non-file: " + inPath);
                return 1;
            }
        }

        final int sessionId = doCreateSession(params.sessionParams,
                params.installerPackageName, params.userId);

        try {
            if (inPath == null && params.sessionParams.sizeBytes == -1) {
                System.err.println("Error: must either specify a package size or an APK file");
                return 1;
            }
            if (doWriteSession(sessionId, inPath, params.sessionParams.sizeBytes, "base.apk",
                    false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            Pair<String, Integer> status = doCommitSession(sessionId, false /*logSuccess*/);
            if (status.second != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            Log.i(TAG, "Package " + status.first + " installed in " + (SystemClock.elapsedRealtime()
                    - startedTime) + " ms");
            System.out.println("Success");
            return 0;
        } finally {
            try {
                mInstaller.abandonSession(sessionId);
            } catch (Exception ignore) {
            }
        }
    }

    private int runInstallAbandon() throws RemoteException {
        final int sessionId = Integer.parseInt(nextArg());
        return doAbandonSession(sessionId, true /*logSuccess*/);
    }

    private int runInstallCommit() throws RemoteException {
        final int sessionId = Integer.parseInt(nextArg());
        return doCommitSession(sessionId, true /*logSuccess*/).second;
    }

    private int runInstallCreate() throws RemoteException {
        final InstallParams installParams = makeInstallParams();
        final int sessionId = doCreateSession(installParams.sessionParams,
                installParams.installerPackageName, installParams.userId);

        // NOTE: adb depends on parsing this string
        System.out.println("Success: created install session [" + sessionId + "]");
        return PackageInstaller.STATUS_SUCCESS;
    }

    private int runInstallWrite() throws RemoteException {
        long sizeBytes = -1;

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("-S")) {
                sizeBytes = Long.parseLong(nextArg());
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }

        final int sessionId = Integer.parseInt(nextArg());
        final String splitName = nextArg();
        final String path = nextArg();
        return doWriteSession(sessionId, path, sizeBytes, splitName, true /*logSuccess*/);
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
        while ((opt = nextOption()) != null) {
            switch (opt) {
                case "-l":
                    sessionParams.installFlags |= PackageManager.INSTALL_FORWARD_LOCK;
                    break;
                case "-r":
                    sessionParams.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
                    break;
                case "-i":
                    params.installerPackageName = nextArg();
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
                    sessionParams.originatingUri = Uri.parse(nextOptionData());
                    break;
                case "--referrer":
                    sessionParams.referrerUri = Uri.parse(nextOptionData());
                    break;
                case "-p":
                    sessionParams.mode = SessionParams.MODE_INHERIT_EXISTING;
                    sessionParams.appPackageName = nextOptionData();
                    if (sessionParams.appPackageName == null) {
                        throw new IllegalArgumentException("Missing inherit package name");
                    }
                    break;
                case "--pkg":
                    sessionParams.appPackageName = nextOptionData();
                    if (sessionParams.appPackageName == null) {
                        throw new IllegalArgumentException("Missing package name");
                    }
                    break;
                case "-S":
                    final long sizeBytes = Long.parseLong(nextOptionData());
                    if (sizeBytes <= 0) {
                        throw new IllegalArgumentException("Size must be positive");
                    }
                    sessionParams.setSize(sizeBytes);
                    break;
                case "--abi":
                    sessionParams.abiOverride = checkAbiArgument(nextOptionData());
                    break;
                case "--ephemeral":
                case "--instant":
                    sessionParams.setInstallAsInstantApp(true /*isInstantApp*/);
                    break;
                case "--full":
                    sessionParams.setInstallAsInstantApp(false /*isInstantApp*/);
                    break;
                case "--user":
                    params.userId = UserHandle.parseUserArg(nextOptionData());
                    break;
                case "--install-location":
                    sessionParams.installLocation = Integer.parseInt(nextOptionData());
                    break;
                case "--force-uuid":
                    sessionParams.installFlags |= PackageManager.INSTALL_FORCE_VOLUME_UUID;
                    sessionParams.volumeUuid = nextOptionData();
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

    private int doCreateSession(SessionParams params, String installerPackageName, int userId)
            throws RemoteException {
        userId = translateUserId(userId, "runInstallCreate");
        if (userId == UserHandle.USER_ALL) {
            userId = UserHandle.USER_SYSTEM;
            params.installFlags |= PackageManager.INSTALL_ALL_USERS;
        }

        final int sessionId = mInstaller.createSession(params, installerPackageName, userId);
        return sessionId;
    }

    private int doWriteSession(int sessionId, String inPath, long sizeBytes, String splitName,
            boolean logSuccess) throws RemoteException {
        if (STDIN_PATH.equals(inPath)) {
            inPath = null;
        } else if (inPath != null) {
            final File file = new File(inPath);
            if (file.isFile()) {
                sizeBytes = file.length();
            }
        }

        final SessionInfo info = mInstaller.getSessionInfo(sessionId);

        PackageInstaller.Session session = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            session = new PackageInstaller.Session(
                    mInstaller.openSession(sessionId));

            if (inPath != null) {
                in = new FileInputStream(inPath);
            } else {
                in = new SizedInputStream(System.in, sizeBytes);
            }
            out = session.openWrite(splitName, 0, sizeBytes);

            int total = 0;
            byte[] buffer = new byte[1024 * 1024];
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
                System.out.println("Success: streamed " + total + " bytes");
            }
            return PackageInstaller.STATUS_SUCCESS;
        } catch (IOException e) {
            System.err.println("Error: failed to write; " + e.getMessage());
            return PackageInstaller.STATUS_FAILURE;
        } finally {
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(session);
        }
    }

    private Pair<String, Integer> doCommitSession(int sessionId, boolean logSuccess)
            throws RemoteException {
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInstaller.openSession(sessionId));

            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());

            final Intent result = receiver.getResult();
            final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_SUCCESS) {
                if (logSuccess) {
                    System.out.println("Success");
                }
            } else {
                System.err.println("Failure ["
                        + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            }
            return new Pair<>(result.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME), status);
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doAbandonSession(int sessionId, boolean logSuccess) throws RemoteException {
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(mInstaller.openSession(sessionId));
            session.abandon();
            if (logSuccess) {
                System.out.println("Success");
            }
            return PackageInstaller.STATUS_SUCCESS;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    class LocalPackageInstallObserver extends PackageInstallObserver {
        boolean finished;
        int result;
        String extraPermission;
        String extraPackage;

        @Override
        public void onPackageInstalled(String name, int status, String msg, Bundle extras) {
            synchronized (this) {
                finished = true;
                result = status;
                if (status == PackageManager.INSTALL_FAILED_DUPLICATE_PERMISSION) {
                    extraPermission = extras.getString(
                            PackageManager.EXTRA_FAILURE_EXISTING_PERMISSION);
                    extraPackage = extras.getString(
                            PackageManager.EXTRA_FAILURE_EXISTING_PACKAGE);
                }
                notifyAll();
            }
        }
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    static class ClearCacheObserver extends IPackageDataObserver.Stub {
        boolean finished;
        boolean result;

        @Override
        public void onRemoveCompleted(String packageName, boolean succeeded) throws RemoteException {
            synchronized (this) {
                finished = true;
                result = succeeded;
                notifyAll();
            }
        }

    }

    static class ClearDataObserver extends IPackageDataObserver.Stub {
        boolean finished;
        boolean result;

        @Override
        public void onRemoveCompleted(String packageName, boolean succeeded) throws RemoteException {
            synchronized (this) {
                finished = true;
                result = succeeded;
                notifyAll();
            }
        }
    }

    /**
     * Displays the package file for a package.
     * @param pckg
     */
    private int displayPackageFilePath(String pckg, int userId) {
        try {
            PackageInfo info = mPm.getPackageInfo(pckg, 0, userId);
            if (info != null && info.applicationInfo != null) {
                System.out.print("package:");
                System.out.println(info.applicationInfo.sourceDir);
                if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                    for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                        System.out.print("package:");
                        System.out.println(splitSourceDir);
                    }
                }
                return 0;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
        }
        return 1;
    }

    private String nextOption() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        mNextArg++;
        return data;
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    private static int showUsage() {
        System.err.println("usage: pm path [--user USER_ID] PACKAGE");
        System.err.println("       pm dump PACKAGE");
        System.err.println("       pm install [-lrtsfdg] [-i PACKAGE] [--user USER_ID]");
        System.err.println("               [-p INHERIT_PACKAGE] [--install-location 0/1/2]");
        System.err.println("               [--originating-uri URI] [---referrer URI]");
        System.err.println("               [--abi ABI_NAME] [--force-sdk]");
        System.err.println("               [--preload] [--instantapp] [--full] [--dont-kill]");
        System.err.println("               [--force-uuid internal|UUID] [--pkg PACKAGE] [-S BYTES] [PATH|-]");
        System.err.println("       pm install-create [-lrtsfdg] [-i PACKAGE] [--user USER_ID]");
        System.err.println("               [-p INHERIT_PACKAGE] [--install-location 0/1/2]");
        System.err.println("               [--originating-uri URI] [---referrer URI]");
        System.err.println("               [--abi ABI_NAME] [--force-sdk]");
        System.err.println("               [--preload] [--instantapp] [--full] [--dont-kill]");
        System.err.println("               [--force-uuid internal|UUID] [--pkg PACKAGE] [-S BYTES]");
        System.err.println("       pm install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH|-]");
        System.err.println("       pm install-commit SESSION_ID");
        System.err.println("       pm install-abandon SESSION_ID");
        System.err.println("       pm uninstall [-k] [--user USER_ID] [--versionCode VERSION_CODE] PACKAGE");
        System.err.println("       pm set-installer PACKAGE INSTALLER");
        System.err.println("       pm move-package PACKAGE [internal|UUID]");
        System.err.println("       pm move-primary-storage [internal|UUID]");
        System.err.println("       pm clear [--user USER_ID] PACKAGE");
        System.err.println("       pm enable [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-user [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-until-used [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm default-state [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm set-user-restriction [--user USER_ID] RESTRICTION VALUE");
        System.err.println("       pm hide [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm unhide [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm grant [--user USER_ID] PACKAGE PERMISSION");
        System.err.println("       pm revoke [--user USER_ID] PACKAGE PERMISSION");
        System.err.println("       pm reset-permissions");
        System.err.println("       pm set-app-link [--user USER_ID] PACKAGE {always|ask|never|undefined}");
        System.err.println("       pm get-app-link [--user USER_ID] PACKAGE");
        System.err.println("       pm set-install-location [0/auto] [1/internal] [2/external]");
        System.err.println("       pm get-install-location");
        System.err.println("       pm set-permission-enforced PERMISSION [true|false]");
        System.err.println("       pm trim-caches DESIRED_FREE_SPACE [internal|UUID]");
        System.err.println("       pm create-user [--profileOf USER_ID] [--managed] [--restricted] [--ephemeral] [--guest] USER_NAME");
        System.err.println("       pm remove-user USER_ID");
        System.err.println("       pm get-max-users");
        System.err.println("");
        System.err.println("NOTE: 'pm list' commands have moved! Run 'adb shell cmd package'");
        System.err.println("  to display the new commands.");
        System.err.println("");
        System.err.println("pm path: print the path to the .apk of the given PACKAGE.");
        System.err.println("");
        System.err.println("pm dump: print system state associated with the given PACKAGE.");
        System.err.println("");
        System.err.println("pm install: install a single legacy package");
        System.err.println("pm install-create: create an install session");
        System.err.println("    -l: forward lock application");
        System.err.println("    -r: allow replacement of existing application");
        System.err.println("    -t: allow test packages");
        System.err.println("    -i: specify package name of installer owning the app");
        System.err.println("    -s: install application on sdcard");
        System.err.println("    -f: install application on internal flash");
        System.err.println("    -d: allow version code downgrade (debuggable packages only)");
        System.err.println("    -p: partial application install (new split on top of existing pkg)");
        System.err.println("    -g: grant all runtime permissions");
        System.err.println("    -S: size in bytes of entire session");
        System.err.println("    --dont-kill: installing a new feature split, don't kill running app");
        System.err.println("    --originating-uri: set URI where app was downloaded from");
        System.err.println("    --referrer: set URI that instigated the install of the app");
        System.err.println("    --pkg: specify expected package name of app being installed");
        System.err.println("    --abi: override the default ABI of the platform");
        System.err.println("    --instantapp: cause the app to be installed as an ephemeral install app");
        System.err.println("    --full: cause the app to be installed as a non-ephemeral full app");
        System.err.println("    --install-location: force the install location:");
        System.err.println("        0=auto, 1=internal only, 2=prefer external");
        System.err.println("    --force-uuid: force install on to disk volume with given UUID");
        System.err.println("    --force-sdk: allow install even when existing app targets platform");
        System.err.println("        codename but new one targets a final API level");
        System.err.println("");
        System.err.println("pm install-write: write a package into existing session; path may");
        System.err.println("  be '-' to read from stdin");
        System.err.println("    -S: size in bytes of package, required for stdin");
        System.err.println("");
        System.err.println("pm install-commit: perform install of fully staged session");
        System.err.println("pm install-abandon: abandon session");
        System.err.println("");
        System.err.println("pm set-installer: set installer package name");
        System.err.println("");
        System.err.println("pm uninstall: removes a package from the system. Options:");
        System.err.println("    -k: keep the data and cache directories around after package removal.");
        System.err.println("");
        System.err.println("pm clear: deletes all data associated with a package.");
        System.err.println("");
        System.err.println("pm enable, disable, disable-user, disable-until-used, default-state:");
        System.err.println("  these commands change the enabled state of a given package or");
        System.err.println("  component (written as \"package/class\").");
        System.err.println("");
        System.err.println("pm grant, revoke: these commands either grant or revoke permissions");
        System.err.println("    to apps. The permissions must be declared as used in the app's");
        System.err.println("    manifest, be runtime permissions (protection level dangerous),");
        System.err.println("    and the app targeting SDK greater than Lollipop MR1.");
        System.err.println("");
        System.err.println("pm reset-permissions: revert all runtime permissions to their default state.");
        System.err.println("");
        System.err.println("pm get-install-location: returns the current install location.");
        System.err.println("    0 [auto]: Let system decide the best location");
        System.err.println("    1 [internal]: Install on internal device storage");
        System.err.println("    2 [external]: Install on external media");
        System.err.println("");
        System.err.println("pm set-install-location: changes the default install location.");
        System.err.println("  NOTE: this is only intended for debugging; using this can cause");
        System.err.println("  applications to break and other undersireable behavior.");
        System.err.println("    0 [auto]: Let system decide the best location");
        System.err.println("    1 [internal]: Install on internal device storage");
        System.err.println("    2 [external]: Install on external media");
        System.err.println("");
        System.err.println("pm trim-caches: trim cache files to reach the given free space.");
        System.err.println("");
        System.err.println("pm create-user: create a new user with the given USER_NAME,");
        System.err.println("  printing the new user identifier of the user.");
        System.err.println("");
        System.err.println("pm remove-user: remove the user with the given USER_IDENTIFIER,");
        System.err.println("  deleting all data associated with that user");
        System.err.println("");
        return 1;
    }
}
