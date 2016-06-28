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
import android.app.ActivityManagerNative;
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
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.internal.content.PackageHelper;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.SizedInputStream;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public final class Pm {
    private static final String TAG = "Pm";

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
        boolean validCommand = false;
        if (args.length < 1) {
            return showUsage();
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

        if ("list".equals(op)) {
            return runList();
        }

        if ("path".equals(op)) {
            return runPath();
        }

        if ("dump".equals(op)) {
            return runDump();
        }

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

        if ("set-installer".equals(op)) {
            return runSetInstaller();
        }

        if ("uninstall".equals(op)) {
            return runUninstall();
        }

        if ("clear".equals(op)) {
            return runClear();
        }

        if ("enable".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        }

        if ("disable".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        }

        if ("disable-user".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
        }

        if ("disable-until-used".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);
        }

        if ("default-state".equals(op)) {
            return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        }

        if ("hide".equals(op)) {
            return runSetHiddenSetting(true);
        }

        if ("unhide".equals(op)) {
            return runSetHiddenSetting(false);
        }

        if ("grant".equals(op)) {
            return runGrantRevokePermission(true);
        }

        if ("revoke".equals(op)) {
            return runGrantRevokePermission(false);
        }

        if ("reset-permissions".equals(op)) {
            return runResetPermissions();
        }

        if ("set-permission-enforced".equals(op)) {
            return runSetPermissionEnforced();
        }

        if ("set-app-link".equals(op)) {
            return runSetAppLink();
        }

        if ("get-app-link".equals(op)) {
            return runGetAppLink();
        }

        if ("set-install-location".equals(op)) {
            return runSetInstallLocation();
        }

        if ("get-install-location".equals(op)) {
            return runGetInstallLocation();
        }

        if ("trim-caches".equals(op)) {
            return runTrimCaches();
        }

        if ("create-user".equals(op)) {
            return runCreateUser();
        }

        if ("remove-user".equals(op)) {
            return runRemoveUser();
        }

        if ("get-max-users".equals(op)) {
            return runGetMaxUsers();
        }

        if ("force-dex-opt".equals(op)) {
            return runForceDexOpt();
        }

        if ("move-package".equals(op)) {
            return runMovePackage();
        }

        if ("move-primary-storage".equals(op)) {
            return runMovePrimaryStorage();
        }

        if ("set-user-restriction".equals(op)) {
            return runSetUserRestriction();
        }

        try {
            if (args.length == 1) {
                if (args[0].equalsIgnoreCase("-l")) {
                    validCommand = true;
                    return runShellCommand("package", new String[] { "list", "package" });
                } else if (args[0].equalsIgnoreCase("-lf")) {
                    validCommand = true;
                    return runShellCommand("package", new String[] { "list", "package", "-f" });
                }
            } else if (args.length == 2) {
                if (args[0].equalsIgnoreCase("-p")) {
                    validCommand = true;
                    return displayPackageFilePath(args[1], UserHandle.USER_SYSTEM);
                }
            }
            return 1;
        } finally {
            if (validCommand == false) {
                if (op != null) {
                    System.err.println("Error: unknown command '" + op + "'");
                }
                showUsage();
            }
        }
    }

    private int runShellCommand(String serviceName, String[] args) {
        final HandlerThread handlerThread = new HandlerThread("results");
        handlerThread.start();
        try {
            ServiceManager.getService(serviceName).shellCommand(
                    FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                    args, new ResultReceiver(new Handler(handlerThread.getLooper())));
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
            public void send(int code, Intent intent, String resolvedType,
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
        final InstallParams params = makeInstallParams();
        final int sessionId = doCreateSession(params.sessionParams,
                params.installerPackageName, params.userId);

        try {
            final String inPath = nextArg();
            if (inPath == null && params.sessionParams.sizeBytes == 0) {
                System.err.println("Error: must either specify a package size or an APK file");
                return 1;
            }
            if (doWriteSession(sessionId, inPath, params.sessionParams.sizeBytes, "base.apk",
                    false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            if (doCommitSession(sessionId, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
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
        return doCommitSession(sessionId, true /*logSuccess*/);
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
                case "-S":
                    sessionParams.setSize(Long.parseLong(nextOptionData()));
                    break;
                case "--abi":
                    sessionParams.abiOverride = checkAbiArgument(nextOptionData());
                    break;
                case "--ephemeral":
                    sessionParams.installFlags |= PackageManager.INSTALL_EPHEMERAL;
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
        if ("-".equals(inPath)) {
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
            byte[] buffer = new byte[65536];
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

    private int doCommitSession(int sessionId, boolean logSuccess) throws RemoteException {
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
            return status;
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

    /**
     * Execute the list sub-command.
     *
     * pm list [package | packages]
     * pm list permission-groups
     * pm list permissions
     * pm list features
     * pm list libraries
     * pm list instrumentation
     */
    private int runList() {
        final String type = nextArg();
        if ("users".equals(type)) {
            return runShellCommand("user", new String[] { "list" });
        }
        return runShellCommand("package", mArgs);
    }

    private int runUninstall() {
        return runShellCommand("package", mArgs);
    }

    private int runPath() {
        int userId = UserHandle.USER_SYSTEM;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                return showUsage();
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            return 1;
        }
        return displayPackageFilePath(pkg, userId);
    }

    private int runDump() {
        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            return 1;
        }
        ActivityManager.dumpPackageStateStatic(FileDescriptor.out, pkg);
        return 0;
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

    // pm set-app-link [--user USER_ID] PACKAGE {always|ask|always-ask|never|undefined}
    private int runSetAppLink() {
        int userId = UserHandle.USER_SYSTEM;

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = Integer.parseInt(nextOptionData());
                if (userId < 0) {
                    System.err.println("Error: user must be >= 0");
                    return 1;
                }
            } else {
                System.err.println("Error: unknown option: " + opt);
                return showUsage();
            }
        }

        // Package name to act on; required
        final String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified.");
            return showUsage();
        }

        // State to apply; {always|ask|never|undefined}, required
        final String modeString = nextArg();
        if (modeString == null) {
            System.err.println("Error: no app link state specified.");
            return showUsage();
        }

        final int newMode;
        switch (modeString.toLowerCase()) {
            case "undefined":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
                break;

            case "always":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS;
                break;

            case "ask":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK;
                break;

            case "always-ask":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK;
                break;

            case "never":
                newMode = INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER;
                break;

            default:
                System.err.println("Error: unknown app link state '" + modeString + "'");
                return 1;
        }

        try {
            final PackageInfo info = mPm.getPackageInfo(pkg, 0, userId);
            if (info == null) {
                System.err.println("Error: package " + pkg + " not found.");
                return 1;
            }

            if ((info.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS) == 0) {
                System.err.println("Error: package " + pkg + " does not handle web links.");
                return 1;
            }

            if (!mPm.updateIntentVerificationStatus(pkg, newMode, userId)) {
                System.err.println("Error: unable to update app link status for " + pkg);
                return 1;
            }
        } catch (Exception e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }

        return 0;
    }

    // pm get-app-link [--user USER_ID] PACKAGE
    private int runGetAppLink() {
        int userId = UserHandle.USER_SYSTEM;

        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = Integer.parseInt(nextOptionData());
                if (userId < 0) {
                    System.err.println("Error: user must be >= 0");
                    return 1;
                }
            } else {
                System.err.println("Error: unknown option: " + opt);
                return showUsage();
            }
        }

        // Package name to act on; required
        final String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified.");
            return showUsage();
        }

        try {
            final PackageInfo info = mPm.getPackageInfo(pkg, 0, userId);
            if (info == null) {
                System.err.println("Error: package " + pkg + " not found.");
                return 1;
            }

            if ((info.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS) == 0) {
                System.err.println("Error: package " + pkg + " does not handle web links.");
                return 1;
            }

            System.out.println(linkStateToString(mPm.getIntentVerificationStatus(pkg, userId)));
        } catch (Exception e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }

        return 0;
    }

    private String linkStateToString(int state) {
        switch (state) {
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED: return "undefined";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK: return "ask";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS: return "always";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER: return "never";
            case INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK : return "always ask";
        }
        return "Unknown link state: " + state;
    }

    private int runSetInstallLocation() {
        int loc;

        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no install location specified.");
            return 1;
        }
        try {
            loc = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println("Error: install location has to be a number.");
            return 1;
        }
        try {
            if (!mPm.setInstallLocation(loc)) {
                System.err.println("Error: install location has to be a number.");
                return 1;
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runGetInstallLocation() {
        try {
            int loc = mPm.getInstallLocation();
            String locStr = "invalid";
            if (loc == PackageHelper.APP_INSTALL_AUTO) {
                locStr = "auto";
            } else if (loc == PackageHelper.APP_INSTALL_INTERNAL) {
                locStr = "internal";
            } else if (loc == PackageHelper.APP_INSTALL_EXTERNAL) {
                locStr = "external";
            }
            System.out.println(loc + "[" + locStr + "]");
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runSetInstaller() throws RemoteException {
        final String targetPackage = nextArg();
        final String installerPackageName = nextArg();

        if (targetPackage == null || installerPackageName == null) {
            throw new IllegalArgumentException(
                    "must provide both target and installer package names");
        }

        mPm.setInstallerPackageName(targetPackage, installerPackageName);
        System.out.println("Success");
        return 0;
    }

    public int runCreateUser() {
        String name;
        int userId = -1;
        int flags = 0;
        String opt;
        while ((opt = nextOption()) != null) {
            if ("--profileOf".equals(opt)) {
                String optionData = nextOptionData();
                if (optionData == null || !isNumber(optionData)) {
                    System.err.println("Error: no USER_ID specified");
                    return showUsage();
                } else {
                    userId = Integer.parseInt(optionData);
                }
            } else if ("--managed".equals(opt)) {
                flags |= UserInfo.FLAG_MANAGED_PROFILE;
            } else if ("--restricted".equals(opt)) {
                flags |= UserInfo.FLAG_RESTRICTED;
            } else if ("--ephemeral".equals(opt)) {
                flags |= UserInfo.FLAG_EPHEMERAL;
            } else if ("--guest".equals(opt)) {
                flags |= UserInfo.FLAG_GUEST;
            } else if ("--demo".equals(opt)) {
                flags |= UserInfo.FLAG_DEMO;
            } else {
                System.err.println("Error: unknown option " + opt);
                return showUsage();
            }
        }
        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no user name specified.");
            return 1;
        }
        name = arg;
        try {
            UserInfo info;
            if ((flags & UserInfo.FLAG_RESTRICTED) != 0) {
                // In non-split user mode, userId can only be SYSTEM
                int parentUserId = userId >= 0 ? userId : UserHandle.USER_SYSTEM;
                info = mUm.createRestrictedProfile(name, parentUserId);
                mAm.addSharedAccountsFromParentUser(parentUserId, userId);
            } else if (userId < 0) {
                info = mUm.createUser(name, flags);
            } else {
                info = mUm.createProfileForUser(name, flags, userId);
            }

            if (info != null) {
                System.out.println("Success: created user id " + info.id);
                return 1;
            } else {
                System.err.println("Error: couldn't create User.");
                return 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    public int runRemoveUser() {
        int userId;
        String arg = nextArg();
        if (arg == null) {
            System.err.println("Error: no user id specified.");
            return 1;
        }
        try {
            userId = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            System.err.println("Error: user id '" + arg + "' is not a number.");
            return 1;
        }
        try {
            if (mUm.removeUser(userId)) {
                System.out.println("Success: removed user");
                return 0;
            } else {
                System.err.println("Error: couldn't remove user id " + userId);
                return 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    public int runGetMaxUsers() {
        System.out.println("Maximum supported users: " + UserManager.getMaxSupportedUsers());
        return 0;
    }

    public int runForceDexOpt() {
        final String packageName = nextArg();
        try {
            mPm.forceDexOpt(packageName);
            return 0;
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public int runMovePackage() {
        final String packageName = nextArg();
        String volumeUuid = nextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }

        try {
            final int moveId = mPm.movePackage(packageName, volumeUuid);

            int status = mPm.getMoveStatus(moveId);
            while (!PackageManager.isMoveStatusFinished(status)) {
                SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);
                status = mPm.getMoveStatus(moveId);
            }

            if (status == PackageManager.MOVE_SUCCEEDED) {
                System.out.println("Success");
                return 0;
            } else {
                System.err.println("Failure [" + status + "]");
                return 1;
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public int runMovePrimaryStorage() {
        String volumeUuid = nextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }

        try {
            final int moveId = mPm.movePrimaryStorage(volumeUuid);

            int status = mPm.getMoveStatus(moveId);
            while (!PackageManager.isMoveStatusFinished(status)) {
                SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);
                status = mPm.getMoveStatus(moveId);
            }

            if (status == PackageManager.MOVE_SUCCEEDED) {
                System.out.println("Success");
                return 0;
            } else {
                System.err.println("Failure [" + status + "]");
                return 1;
            }
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    public int runSetUserRestriction() {
        int userId = UserHandle.USER_SYSTEM;
        String opt = nextOption();
        if (opt != null && "--user".equals(opt)) {
            String arg = nextArg();
            if (arg == null || !isNumber(arg)) {
                System.err.println("Error: valid userId not specified");
                return 1;
            }
            userId = Integer.parseInt(arg);
        }

        String restriction = nextArg();
        String arg = nextArg();
        boolean value;
        if ("1".equals(arg)) {
            value = true;
        } else if ("0".equals(arg)) {
            value = false;
        } else {
            System.err.println("Error: valid value not specified");
            return 1;
        }
        try {
            mUm.setUserRestriction(restriction, value, userId);
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            return 1;
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

    private int runClear() {
        int userId = UserHandle.USER_SYSTEM;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                return showUsage();
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            return showUsage();
        }

        ClearDataObserver obs = new ClearDataObserver();
        try {
            ActivityManagerNative.getDefault().clearApplicationUserData(pkg, obs, userId);
            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (obs.result) {
                System.out.println("Success");
                return 0;
            } else {
                System.err.println("Failed");
                return 1;
            }
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private static String enabledSettingToString(int state) {
        switch (state) {
            case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                return "default";
            case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                return "enabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED:
                return "disabled";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER:
                return "disabled-user";
            case PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                return "disabled-until-used";
        }
        return "unknown";
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    private int runSetEnabledSetting(int state) {
        int userId = UserHandle.USER_SYSTEM;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                return showUsage();
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package or component specified");
            return showUsage();
        }
        ComponentName cn = ComponentName.unflattenFromString(pkg);
        if (cn == null) {
            try {
                mPm.setApplicationEnabledSetting(pkg, state, 0, userId,
                        "shell:" + android.os.Process.myUid());
                System.out.println("Package " + pkg + " new state: "
                        + enabledSettingToString(
                        mPm.getApplicationEnabledSetting(pkg, userId)));
                return 0;
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
                return 1;
            }
        } else {
            try {
                mPm.setComponentEnabledSetting(cn, state, 0, userId);
                System.out.println("Component " + cn.toShortString() + " new state: "
                        + enabledSettingToString(
                        mPm.getComponentEnabledSetting(cn, userId)));
                return 0;
            } catch (RemoteException e) {
                System.err.println(e.toString());
                System.err.println(PM_NOT_RUNNING_ERR);
                return 1;
            }
        }
    }

    private int runSetHiddenSetting(boolean state) {
        int userId = UserHandle.USER_SYSTEM;
        String option = nextOption();
        if (option != null && option.equals("--user")) {
            String optionData = nextOptionData();
            if (optionData == null || !isNumber(optionData)) {
                System.err.println("Error: no USER_ID specified");
                return showUsage();
            } else {
                userId = Integer.parseInt(optionData);
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package or component specified");
            return showUsage();
        }
        try {
            mPm.setApplicationHiddenSettingAsUser(pkg, state, userId);
            System.out.println("Package " + pkg + " new hidden state: "
                    + mPm.getApplicationHiddenSettingAsUser(pkg, userId));
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        }
    }

    private int runGrantRevokePermission(boolean grant) {
        int userId = UserHandle.USER_SYSTEM;

        String opt = null;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = Integer.parseInt(nextArg());
            }
        }

        String pkg = nextArg();
        if (pkg == null) {
            System.err.println("Error: no package specified");
            return showUsage();
        }
        String perm = nextArg();
        if (perm == null) {
            System.err.println("Error: no permission specified");
            return showUsage();
        }

        try {
            if (grant) {
                mPm.grantRuntimePermission(pkg, perm, userId);
            } else {
                mPm.revokeRuntimePermission(pkg, perm, userId);
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            return showUsage();
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
    }

    private int runResetPermissions() {
        try {
            mPm.resetRuntimePermissions();
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            return showUsage();
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
    }

    private int runSetPermissionEnforced() {
        final String permission = nextArg();
        if (permission == null) {
            System.err.println("Error: no permission specified");
            return showUsage();
        }
        final String enforcedRaw = nextArg();
        if (enforcedRaw == null) {
            System.err.println("Error: no enforcement specified");
            return showUsage();
        }
        final boolean enforced = Boolean.parseBoolean(enforcedRaw);
        try {
            mPm.setPermissionEnforced(permission, enforced);
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            return showUsage();
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
        }
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

    private int runTrimCaches() {
        String size = nextArg();
        if (size == null) {
            System.err.println("Error: no size specified");
            return showUsage();
        }
        int len = size.length();
        long multiplier = 1;
        if (len > 1) {
            char c = size.charAt(len-1);
            if (c == 'K' || c == 'k') {
                multiplier = 1024L;
            } else if (c == 'M' || c == 'm') {
                multiplier = 1024L*1024L;
            } else if (c == 'G' || c == 'g') {
                multiplier = 1024L*1024L*1024L;
            } else {
                System.err.println("Invalid suffix: " + c);
                return showUsage();
            }
            size = size.substring(0, len-1);
        }
        long sizeVal;
        try {
            sizeVal = Long.parseLong(size) * multiplier;
        } catch (NumberFormatException e) {
            System.err.println("Error: expected number at: " + size);
            return showUsage();
        }
        String volumeUuid = nextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }
        ClearDataObserver obs = new ClearDataObserver();
        try {
            mPm.freeStorageAndNotify(volumeUuid, sizeVal, obs);
            synchronized (obs) {
                while (!obs.finished) {
                    try {
                        obs.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
            return 0;
        } catch (RemoteException e) {
            System.err.println(e.toString());
            System.err.println(PM_NOT_RUNNING_ERR);
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("Bad argument: " + e.toString());
            return showUsage();
        } catch (SecurityException e) {
            System.err.println("Operation not allowed: " + e.toString());
            return 1;
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
        System.err.println("       pm install [-lrtsfd] [-i PACKAGE] [--user USER_ID] [PATH]");
        System.err.println("       pm install-create [-lrtsfdp] [-i PACKAGE] [-S BYTES]");
        System.err.println("               [--install-location 0/1/2]");
        System.err.println("               [--force-uuid internal|UUID]");
        System.err.println("       pm install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH]");
        System.err.println("       pm install-commit SESSION_ID");
        System.err.println("       pm install-abandon SESSION_ID");
        System.err.println("       pm uninstall [-k] [--user USER_ID] PACKAGE");
        System.err.println("       pm set-installer PACKAGE INSTALLER");
        System.err.println("       pm move-package PACKAGE [internal|UUID]");
        System.err.println("       pm move-primary-storage [internal|UUID]");
        System.err.println("       pm clear [--user USER_ID] PACKAGE");
        System.err.println("       pm enable [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-user [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm disable-until-used [--user USER_ID] PACKAGE_OR_COMPONENT");
        System.err.println("       pm default-state [--user USER_ID] PACKAGE_OR_COMPONENT");
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
        System.err.println("    -r: replace existing application");
        System.err.println("    -t: allow test packages");
        System.err.println("    -i: specify the installer package name");
        System.err.println("    -s: install application on sdcard");
        System.err.println("    -f: install application on internal flash");
        System.err.println("    -d: allow version code downgrade (debuggable packages only)");
        System.err.println("    -p: partial application install");
        System.err.println("    -g: grant all runtime permissions");
        System.err.println("    -S: size in bytes of entire session");
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
