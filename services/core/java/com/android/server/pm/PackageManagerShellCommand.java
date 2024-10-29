/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;

import static android.Manifest.permission.GET_APP_METADATA;
import static android.content.pm.PackageInstaller.LOCATION_DATA_APP;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKED_COMPAT;
import static android.content.pm.PackageManager.FLAG_PERMISSION_REVOKE_WHEN_REQUESTED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_FIXED;
import static android.content.pm.PackageManager.FLAG_PERMISSION_USER_SET;
import static android.content.pm.PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;
import static android.content.pm.PackageManager.RESTRICTION_HIDE_NOTIFICATIONS;
import static android.content.pm.PackageManager.RESTRICTION_NONE;

import static com.android.server.LocalManagerRegistry.ManagerNotFoundException;

import android.accounts.IAccountManager;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.role.RoleManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.FeatureInfo;
import android.content.pm.Flags;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageInstaller;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.ModuleInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SuspendDialogInfo;
import android.content.pm.UserInfo;
import android.content.pm.VersionedPackage;
import android.content.pm.dex.DexMetadataHelper;
import android.content.pm.dex.ISnapshotRuntimeProfileCallback;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.content.rollback.RollbackManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IUserManager;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseInputStream;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.incremental.V4Signature;
import android.os.storage.StorageManager;
import android.permission.PermissionManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.IntArray;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.content.InstallLocationUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.SystemConfig;
import com.android.server.art.ArtManagerLocal;
import com.android.server.art.ReasonMapping;
import com.android.server.art.model.DexoptParams;
import com.android.server.pm.PackageManagerShellCommandDataLoader.Metadata;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.permission.PermissionAllowlist;
import com.android.server.pm.verify.domain.DomainVerificationShell;

import libcore.io.IoUtils;
import libcore.io.Streams;
import libcore.util.HexEncoding;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

class PackageManagerShellCommand extends ShellCommand {
    /** Path for streaming APK content */
    private static final String STDIN_PATH = "-";
    /** Path where ART profiles snapshots are dumped for the shell user */
    private final static String ART_PROFILE_SNAPSHOT_DEBUG_LOCATION = "/data/misc/profman/";
    private static final int DEFAULT_STAGED_READY_TIMEOUT_MS = 60 * 1000;
    private static final String TAG = "PackageManagerShellCommand";
    private static final Set<String> UNSUPPORTED_INSTALL_CMD_OPTS = Set.of(
            "--multi-package"
    );
    private static final Set<String> UNSUPPORTED_SESSION_CREATE_OPTS = Collections.emptySet();
    private static final Map<String, Integer> SUPPORTED_PERMISSION_FLAGS = new ArrayMap<>();
    private static final List<String> SUPPORTED_PERMISSION_FLAGS_LIST;
    static {
        SUPPORTED_PERMISSION_FLAGS_LIST = List.of("review-required", "revoked-compat",
                "revoke-when-requested", "user-fixed", "user-set");
        SUPPORTED_PERMISSION_FLAGS.put("user-set", FLAG_PERMISSION_USER_SET);
        SUPPORTED_PERMISSION_FLAGS.put("user-fixed", FLAG_PERMISSION_USER_FIXED);
        SUPPORTED_PERMISSION_FLAGS.put("revoked-compat", FLAG_PERMISSION_REVOKED_COMPAT);
        SUPPORTED_PERMISSION_FLAGS.put("review-required", FLAG_PERMISSION_REVIEW_REQUIRED);
        SUPPORTED_PERMISSION_FLAGS.put("revoke-when-requested",
                FLAG_PERMISSION_REVOKE_WHEN_REQUESTED);
    }
    // For backward compatibility. DO NOT add new commands here. New ART Service commands should be
    // added under the "art" namespace.
    private static final Set<String> ART_SERVICE_COMMANDS = Set.of("compile",
            "reconcile-secondary-dex-files", "force-dex-opt", "bg-dexopt-job",
            "cancel-bg-dexopt-job", "delete-dexopt", "dump-profiles", "snapshot-profile", "art");

    final IPackageManager mInterface;
    private final PackageManagerInternal mPm;
    final LegacyPermissionManagerInternal mLegacyPermissionManager;
    final PermissionManager mPermissionManager;
    final Context mContext;
    final DomainVerificationShell mDomainVerificationShell;
    final private WeakHashMap<String, Resources> mResourceCache =
            new WeakHashMap<String, Resources>();
    int mTargetUser;
    boolean mBrief;
    boolean mComponents;
    int mQueryFlags;

    private static final SecureRandom RANDOM = new SecureRandom();

    PackageManagerShellCommand(@NonNull IPackageManager packageManager,
            @NonNull Context context, @NonNull DomainVerificationShell domainVerificationShell) {
        mInterface = packageManager;
        mPm = LocalServices.getService(PackageManagerInternal.class);
        mLegacyPermissionManager = LocalServices.getService(LegacyPermissionManagerInternal.class);
        mPermissionManager = context.getSystemService(PermissionManager.class);
        mContext = context;
        mDomainVerificationShell = domainVerificationShell;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "help":
                    onHelp();
                    return 0;
                case "path":
                    return runPath();
                case "dump":
                    return runDump();
                case "dump-package":
                    return runDumpPackage();
                case "list":
                    return runList();
                case "gc":
                    return runGc();
                case "resolve-activity":
                    return runResolveActivity();
                case "query-activities":
                    return runQueryIntentActivities();
                case "query-services":
                    return runQueryIntentServices();
                case "query-receivers":
                    return runQueryIntentReceivers();
                case "install":
                    return runInstall();
                case "install-streaming":
                    return runStreamingInstall();
                case "install-incremental":
                    return runIncrementalInstall();
                case "install-abandon":
                case "install-destroy":
                    return runInstallAbandon();
                case "install-commit":
                    return runInstallCommit();
                case "install-create":
                    return runInstallCreate();
                case "install-remove":
                    return runInstallRemove();
                case "install-write":
                    return runInstallWrite();
                case "install-existing":
                    return runInstallExisting();
                case "set-install-location":
                    return runSetInstallLocation();
                case "get-install-location":
                    return runGetInstallLocation();
                case "install-add-session":
                    return runInstallAddSession();
                case "install-set-pre-verified-domains":
                    return runInstallSetPreVerifiedDomains();
                case "install-get-pre-verified-domains":
                    return runInstallGetPreVerifiedDomains();
                case "move-package":
                    return runMovePackage();
                case "move-primary-storage":
                    return runMovePrimaryStorage();
                case "uninstall":
                    return runUninstall();
                case "clear":
                    return runClear();
                case "get-archived-package-metadata":
                    return runGetArchivedPackageMetadata();
                case "get-package-storage-stats":
                    return runGetPackageStorageStats();
                case "install-archived":
                    return runArchivedInstall();
                case "enable":
                    return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
                case "disable":
                    return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
                case "disable-user":
                    return runSetEnabledSetting(
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
                case "disable-until-used":
                    return runSetEnabledSetting(
                            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED);
                case "default-state":
                    return runSetEnabledSetting(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
                case "hide":
                    return runSetHiddenSetting(true);
                case "unhide":
                    return runSetHiddenSetting(false);
                case "unstop":
                    return runSetStoppedState(false);
                case "suspend":
                    return runSuspend(true, 0);
                case "suspend-quarantine":
                    return runSuspend(true, PackageManager.FLAG_SUSPEND_QUARANTINED);
                case "unsuspend":
                    return runSuspend(false, 0);
                case "set-distracting-restriction":
                    return runSetDistractingRestriction();
                case "get-distracting-restriction":
                    return runGetDistractingRestriction();
                case "grant":
                    return runGrantRevokePermission(true);
                case "revoke":
                    return runGrantRevokePermission(false);
                case "reset-permissions":
                    return runResetPermissions();
                case "set-permission-flags":
                    return setOrClearPermissionFlags(true);
                case "clear-permission-flags":
                    return setOrClearPermissionFlags(false);
                case "set-permission-enforced":
                    return runSetPermissionEnforced();
                case "get-privapp-permissions":
                    return runGetPrivappPermissions();
                case "get-privapp-deny-permissions":
                    return runGetPrivappDenyPermissions();
                case "get-oem-permissions":
                    return runGetOemPermissions();
                case "get-signature-permission-allowlist":
                    return runGetSignaturePermissionAllowlist();
                case "get-shared-uid-allowlist":
                    return runGetSharedUidAllowlist();
                case "trim-caches":
                    return runTrimCaches();
                case "create-user":
                    return runCreateUser();
                case "remove-user":
                    return runRemoveUser();
                case "mark-guest-for-deletion":
                    return runMarkGuestForDeletion();
                case "rename-user":
                    return runRenameUser();
                case "set-user-restriction":
                    return runSetUserRestriction();
                case "get-user-restriction":
                    return runGetUserRestriction();
                case "supports-multiple-users":
                    return runSupportsMultipleUsers();
                case "get-max-users":
                    return runGetMaxUsers();
                case "get-max-running-users":
                    return runGetMaxRunningUsers();
                case "set-home-activity":
                    return runSetHomeActivity();
                case "set-installer":
                    return runSetInstaller();
                case "get-instantapp-resolver":
                    return runGetInstantAppResolver();
                case "has-feature":
                    return runHasFeature();
                case "set-harmful-app-warning":
                    return runSetHarmfulAppWarning();
                case "get-harmful-app-warning":
                    return runGetHarmfulAppWarning();
                case "get-stagedsessions":
                    return runListStagedSessions();
                case "uninstall-system-updates":
                    String packageName = getNextArg();
                    return uninstallSystemUpdates(packageName);
                case "rollback-app":
                    return runRollbackApp();
                case "get-moduleinfo":
                    return runGetModuleInfo();
                case "log-visibility":
                    return runLogVisibility();
                case "bypass-staged-installer-check":
                    return runBypassStagedInstallerCheck();
                case "bypass-allowed-apex-update-check":
                    return runBypassAllowedApexUpdateCheck();
                case "disable-verification-for-uid":
                    return runDisableVerificationForUid();
                case "set-silent-updates-policy":
                    return runSetSilentUpdatesPolicy();
                case "get-app-metadata":
                    return runGetAppMetadata();
                case "clear-package-preferred-activities":
                    return runClearPackagePreferredActivities();
                case "wait-for-handler":
                    return runWaitForHandler(/* forBackgroundHandler= */ false);
                case "wait-for-background-handler":
                    return runWaitForHandler(/* forBackgroundHandler= */ true);
                case "archive":
                    return runArchive();
                case "request-unarchive":
                    return runUnarchive();
                case "get-domain-verification-agent":
                    return runGetDomainVerificationAgent();
                case "get-verification-policy":
                    return runGetVerificationPolicy();
                case "set-verification-policy":
                    return runSetVerificationPolicy();
                default: {
                    if (ART_SERVICE_COMMANDS.contains(cmd)) {
                        return runArtServiceCommand();
                    }

                    Boolean domainVerificationResult =
                            mDomainVerificationShell.runCommand(this, cmd);
                    if (domainVerificationResult != null) {
                        return domainVerificationResult ? 0 : 1;
                    }

                    String nextArg = getNextArg();
                    if (nextArg == null) {
                        if (cmd.equalsIgnoreCase("-l")) {
                            return runListPackages(false);
                        } else if (cmd.equalsIgnoreCase("-lf")) {
                            return runListPackages(true);
                        }
                    } else if (getNextArg() == null) {
                        if (cmd.equalsIgnoreCase("-p")) {
                            return displayPackageFilePath(nextArg, UserHandle.USER_SYSTEM);
                        }
                    }
                    return handleDefaultCommands(cmd);
                }
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    /**
     * Shows module info
     *
     * Usage: get-moduleinfo [--all | --installed] [module-name]
     * Example: get-moduleinfo, get-moduleinfo --all, get-moduleinfo xyz
     */
    private int runGetModuleInfo() {
        final PrintWriter pw = getOutPrintWriter();
        int flags = 0;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--all":
                    flags |= PackageManager.MATCH_ALL;
                    break;
                case "--installed":
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return -1;
            }
        }

        String moduleName = getNextArg();
        try {
            if (moduleName != null) {
                ModuleInfo m = mInterface.getModuleInfo(moduleName, flags);
                pw.println(m.toString() + " packageName: " + m.getPackageName());

            } else {
                List<ModuleInfo> modules = mInterface.getInstalledModules(flags);
                for (ModuleInfo m: modules) {
                    pw.println(m.toString() + " packageName: " + m.getPackageName());
                }
            }
        } catch (RemoteException e) {
            pw.println("Failure [" + e.getClass().getName() + " - " + e.getMessage() + "]");
            return -1;
        }
        return 1;
    }

    private int runLogVisibility() {
        final PrintWriter pw = getOutPrintWriter();
        boolean enable = true;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--disable":
                    enable = false;
                    break;
                case "--enable":
                    enable = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return -1;
            }
        }

        String packageName = getNextArg();
        if (packageName != null) {
            LocalServices.getService(PackageManagerInternal.class)
                    .setVisibilityLogging(packageName, enable);
        } else {
            getErrPrintWriter().println("Error: no package specified");
            return -1;
        }
        return 1;
    }

    private int runBypassStagedInstallerCheck() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            mInterface.getPackageInstaller()
                    .bypassNextStagedInstallerCheck(Boolean.parseBoolean(getNextArg()));
            return 0;
        } catch (RemoteException e) {
            pw.println("Failure ["
                    + e.getClass().getName() + " - "
                    + e.getMessage() + "]");
            return -1;
        }
    }

    private int runBypassAllowedApexUpdateCheck() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            mInterface.getPackageInstaller()
                    .bypassNextAllowedApexUpdateCheck(Boolean.parseBoolean(getNextArg()));
            return 0;
        } catch (RemoteException e) {
            pw.println("Failure ["
                    + e.getClass().getName() + " - "
                    + e.getMessage() + "]");
            return -1;
        }
    }

    private int runDisableVerificationForUid() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            int uid = Integer.parseInt(getNextArgRequired());
            var amInternal = LocalServices.getService(ActivityManagerInternal.class);
            boolean isInstrumented =
                    amInternal.getInstrumentationSourceUid(uid) != Process.INVALID_UID;
            if (isInstrumented) {
                mInterface.getPackageInstaller().disableVerificationForUid(uid);
                return 0;
            } else {
                // Only available for testing
                pw.println("Error: must specify an instrumented uid");
                return -1;
            }
        } catch (RemoteException e) {
            pw.println("Failure ["
                    + e.getClass().getName() + " - "
                    + e.getMessage() + "]");
            return -1;
        }
    }

    private int uninstallSystemUpdates(String packageName) {
        final PrintWriter pw = getOutPrintWriter();
        boolean failedUninstalls = false;
        try {
            final IPackageInstaller installer = mInterface.getPackageInstaller();
            final List<ApplicationInfo> list;
            if (packageName == null) {
                final ParceledListSlice<ApplicationInfo> packages =
                        mInterface.getInstalledApplications(PackageManager.MATCH_SYSTEM_ONLY
                                        | PackageManager.MATCH_UNINSTALLED_PACKAGES,
                                UserHandle.USER_SYSTEM);
                list = packages.getList();
            } else {
                list = new ArrayList<>(1);
                list.add(mInterface.getApplicationInfo(packageName, PackageManager.MATCH_SYSTEM_ONLY
                                | PackageManager.MATCH_UNINSTALLED_PACKAGES,
                        UserHandle.USER_SYSTEM));
            }
            for (ApplicationInfo info : list) {
                if (info.isUpdatedSystemApp()) {
                    pw.println("Uninstalling updates to " + info.packageName + "...");
                    final LocalIntentReceiver receiver = new LocalIntentReceiver();
                    installer.uninstall(new VersionedPackage(info.packageName,
                                    info.versionCode), null /*callerPackageName*/, 0 /* flags */,
                            receiver.getIntentSender(), 0);

                    final Intent result = receiver.getResult();
                    final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                            PackageInstaller.STATUS_FAILURE);
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        failedUninstalls = true;
                        pw.println("Couldn't uninstall package: " + info.packageName);
                    }
                }
            }
        } catch (RemoteException e) {
            pw.println("Failure ["
                    + e.getClass().getName() + " - "
                    + e.getMessage() + "]");
            return 0;
        }
        if (failedUninstalls) {
            return 0;
        }
        pw.println("Success");
        return 1;
    }

    private int runRollbackApp() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();

        String opt;
        long stagedReadyTimeoutMs = DEFAULT_STAGED_READY_TIMEOUT_MS;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--staged-ready-timeout":
                    stagedReadyTimeoutMs = Long.parseLong(getNextArgRequired());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }
        final String packageName = getNextArgRequired();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        final Context shellPackageContext;
        try {
            shellPackageContext = mContext.createPackageContextAsUser(
                    "com.android.shell", 0, Binder.getCallingUserHandle());
        } catch (NameNotFoundException e) {
            // should not happen
            throw new RuntimeException(e);
        }

        final LocalIntentReceiver receiver = new LocalIntentReceiver();
        RollbackManager rm = shellPackageContext.getSystemService(RollbackManager.class);
        RollbackInfo rollback = null;
        for (RollbackInfo r : rm.getAvailableRollbacks()) {
            for (PackageRollbackInfo info : r.getPackages()) {
                if (packageName.equals(info.getPackageName())) {
                    rollback = r;
                    break;
                }
            }
        }

        if (rollback == null) {
            pw.println("No available rollbacks for: " + packageName);
            return 1;
        }

        rm.commitRollback(rollback.getRollbackId(),
                Collections.emptyList(), receiver.getIntentSender());

        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(RollbackManager.EXTRA_STATUS,
                RollbackManager.STATUS_FAILURE);

        if (status != RollbackManager.STATUS_SUCCESS) {
            pw.println("Failure ["
                    + result.getStringExtra(RollbackManager.EXTRA_STATUS_MESSAGE) + "]");
            return 1;
        }

        if (rollback.isStaged() && stagedReadyTimeoutMs > 0) {
            final int committedSessionId = rollback.getCommittedSessionId();
            return doWaitForStagedSessionReady(committedSessionId, stagedReadyTimeoutMs, pw);
        }

        pw.println("Success");
        return 0;

    }

    private void setParamsSize(InstallParams params, List<String> inPaths) {
        if (params.sessionParams.sizeBytes != -1 || STDIN_PATH.equals(inPaths.get(0))) {
            return;
        }

        long sessionSize = 0;

        ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        for (String inPath : inPaths) {
            final ParcelFileDescriptor fd = openFileForSystem(inPath, "r");
            if (fd == null) {
                getErrPrintWriter().println("Error: Can't open file: " + inPath);
                throw new IllegalArgumentException("Error: Can't open file: " + inPath);
            }
            try {
                ParseResult<ApkLite> apkLiteResult = ApkLiteParseUtils.parseApkLite(
                        input.reset(), fd.getFileDescriptor(), inPath, 0);
                if (apkLiteResult.isError()) {
                    throw new IllegalArgumentException(
                            "Error: Failed to parse APK file: " + inPath + ": "
                                    + apkLiteResult.getErrorMessage(),
                            apkLiteResult.getException());
                }
                final ApkLite apkLite = apkLiteResult.getResult();
                final PackageLite pkgLite = new PackageLite(null, apkLite.getPath(), apkLite,
                        null /* splitNames */, null /* isFeatureSplits */,
                        null /* usesSplitNames */, null /* configForSplit */,
                        null /* splitApkPaths */, null /* splitRevisionCodes */,
                        apkLite.getTargetSdkVersion(), null /* requiredSplitTypes */,
                        null /* splitTypes */);
                sessionSize += InstallLocationUtils.calculateInstalledSize(pkgLite,
                        params.sessionParams.abiOverride, fd.getFileDescriptor());
            } catch (IOException e) {
                getErrPrintWriter().println("Error: Failed to parse APK file: " + inPath);
                throw new IllegalArgumentException(
                        "Error: Failed to parse APK file: " + inPath, e);
            } finally {
                try {
                    fd.close();
                } catch (IOException e) {
                }
            }
        }

        params.sessionParams.setSize(sessionSize);
    }
    /**
     * Displays the package file for a package.
     * @param pckg
     */
    private int displayPackageFilePath(String pckg, int userId) throws RemoteException {
        PackageInfo info = mInterface.getPackageInfo(pckg, PackageManager.MATCH_APEX, userId);
        if (info != null && info.applicationInfo != null) {
            final PrintWriter pw = getOutPrintWriter();
            pw.print("package:");
            pw.println(info.applicationInfo.sourceDir);
            if (!ArrayUtils.isEmpty(info.applicationInfo.splitSourceDirs)) {
                for (String splitSourceDir : info.applicationInfo.splitSourceDirs) {
                    pw.print("package:");
                    pw.println(splitSourceDir);
                }
            }
            return 0;
        }
        return 1;
    }

    private int runPath() throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }

        String pkg = getNextArgRequired();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runPath");
        return displayPackageFilePath(pkg, translatedUserId);
    }

    private int runList() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to list");
            return -1;
        }
        switch(type) {
            case "features":
                return runListFeatures();
            case "instrumentation":
                return runListInstrumentation();
            case "libraries":
                return runListLibraries();
            case "package":
            case "packages":
                return runListPackages(false /*showSourceDir*/);
            case "permission-groups":
                return runListPermissionGroups();
            case "permissions":
                return runListPermissions();
            case "staged-sessions":
                return runListStagedSessions();
            case "sdks":
                return runListSdks();
            case "users":
                ServiceManager.getService("user").shellCommand(
                        getInFileDescriptor(), getOutFileDescriptor(), getErrFileDescriptor(),
                        new String[] { "list" }, getShellCallback(), adoptResultReceiver());
                return 0;
            case "initial-non-stopped-system-packages":
                return runListInitialNonStoppedSystemPackages();
        }
        pw.println("Error: unknown list type '" + type + "'");
        return -1;
    }

    private int runGc() throws RemoteException {
        Runtime.getRuntime().gc();
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Ok");
        return 0;
    }

    private int runListInitialNonStoppedSystemPackages() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<String> list = mInterface.getInitialNonStoppedSystemPackages();

        Collections.sort(list);

        for (String pkgName : list) {
            pw.print("package:");
            pw.print(pkgName);
            pw.println();
        }

        return 0;
    }

    private int runListFeatures() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<FeatureInfo> list = mInterface.getSystemAvailableFeatures().getList();

        // sort by name
        Collections.sort(list, new Comparator<FeatureInfo>() {
            public int compare(FeatureInfo o1, FeatureInfo o2) {
                if (o1.name == o2.name) return 0;
                if (o1.name == null) return -1;
                if (o2.name == null) return 1;
                return o1.name.compareTo(o2.name);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            FeatureInfo fi = list.get(p);
            pw.print("feature:");
            if (fi.name != null) {
                pw.print(fi.name);
                if (fi.version > 0) {
                    pw.print("=");
                    pw.print(fi.version);
                }
                pw.println();
            } else {
                pw.println("reqGlEsVersion=0x"
                        + Integer.toHexString(fi.reqGlEsVersion));
            }
        }
        return 0;
    }

    private int runListInstrumentation() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean showSourceDir = false;
        String targetPackage = null;

        try {
            String opt;
            while ((opt = getNextArg()) != null) {
                switch (opt) {
                    case "-f":
                        showSourceDir = true;
                        break;
                    default:
                        if (opt.charAt(0) != '-') {
                            targetPackage = opt;
                        } else {
                            pw.println("Error: Unknown option: " + opt);
                            return -1;
                        }
                        break;
                }
            }
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        final List<InstrumentationInfo> list =
                mInterface.queryInstrumentationAsUser(
                        targetPackage, PackageManager.MATCH_KNOWN_PACKAGES, UserHandle.USER_SYSTEM)
                        .getList();

        // sort by target package
        Collections.sort(list, new Comparator<InstrumentationInfo>() {
            public int compare(InstrumentationInfo o1, InstrumentationInfo o2) {
                return o1.targetPackage.compareTo(o2.targetPackage);
            }
        });

        final int count = (list != null) ? list.size() : 0;
        for (int p = 0; p < count; p++) {
            final InstrumentationInfo ii = list.get(p);
            pw.print("instrumentation:");
            if (showSourceDir) {
                pw.print(ii.sourceDir);
                pw.print("=");
            }
            final ComponentName cn = new ComponentName(ii.packageName, ii.name);
            pw.print(cn.flattenToShortString());
            pw.print(" (target=");
            pw.print(ii.targetPackage);
            pw.println(")");
        }
        return 0;
    }

    private int runListLibraries() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean verbose = false;
        String opt;
        while ((opt = getNextArg()) != null) {
            switch (opt) {
                case "-v":
                    verbose = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return -1;
            }
        }

        final Map<String, String> namesAndPaths = mInterface.getSystemSharedLibraryNamesAndPaths();
        if (namesAndPaths.isEmpty()) {
            return 0;
        }

        // sort by name
        final List<String> libs = new ArrayList<>(namesAndPaths.keySet());
        Collections.sort(libs, (o1, o2) -> {
                if (o1 == o2) return 0;
                if (o1 == null) return -1;
                if (o2 == null) return 1;
                return o1.compareTo(o2);
        });

        for (int i = 0; i < libs.size(); i++) {
            String lib = libs.get(i);
            pw.print("library:");
            pw.print(lib);
            if (verbose) {
                pw.print(" path:");
                pw.print(namesAndPaths.get(lib));
            }
            pw.println();
        }
        return 0;
    }

    private int runListPackages(boolean showSourceDir) throws RemoteException {
        return runListPackages(showSourceDir, false);
    }

    private int runListSdks() throws RemoteException {
        return runListPackages(false, true);
    }

    private int runListPackages(boolean showSourceDir, boolean showSdks) throws RemoteException {
        final String prefix = showSdks ? "sdk:" : "package:";
        final PrintWriter pw = getOutPrintWriter();
        int getFlags = 0;
        boolean listDisabled = false, listEnabled = false;
        boolean listSystem = false, listThirdParty = false;
        boolean listInstaller = false;
        boolean showUid = false;
        boolean showVersionCode = false;
        boolean listQuarantinedOnly = false;
        boolean listApexOnly = false;
        boolean showStopped = false;
        int uid = -1;
        int defaultUserId = UserHandle.USER_ALL;
        try {
            String opt;
            while ((opt = getNextOption()) != null) {
                switch (opt) {
                    case "-d":
                        listDisabled = true;
                        break;
                    case "-e":
                        listEnabled = true;
                        break;
                    case "-a":
                        getFlags |= PackageManager.MATCH_KNOWN_PACKAGES;
                        getFlags |= PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS;
                        break;
                    case "-f":
                        showSourceDir = true;
                        break;
                    case "-i":
                        listInstaller = true;
                        break;
                    case "-l":
                        // old compat
                        break;
                    case "-s":
                        listSystem = true;
                        break;
                    case "-q":
                        listQuarantinedOnly = true;
                        break;
                    case "-U":
                        showUid = true;
                        break;
                    case "-u":
                        getFlags |= PackageManager.MATCH_UNINSTALLED_PACKAGES;
                        break;
                    case "-3":
                        listThirdParty = true;
                        break;
                    case "--show-versioncode":
                        showVersionCode = true;
                        break;
                    case "--apex-only":
                        getFlags |= PackageManager.MATCH_APEX;
                        listApexOnly = true;
                        break;
                    case "--factory-only":
                        getFlags |= PackageManager.MATCH_FACTORY_ONLY;
                        break;
                    case "--user":
                        defaultUserId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    case "--uid":
                        showUid = true;
                        uid = Integer.parseInt(getNextArgRequired());
                        break;
                    case "--match-libraries":
                        getFlags |= PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;
                        break;
                    case "--show-stopped":
                        showStopped = true;
                        break;
                    default:
                        pw.println("Error: Unknown option: " + opt);
                        return -1;
                }
            }
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        final String filter = getNextArg();

        int[] userIds = {defaultUserId};
        if (defaultUserId == UserHandle.USER_ALL) {
            final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
            userIds = umi.getUserIds();
        }
        if (showSdks) {
            getFlags |= PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES;
        }

        // Build a map of packages to a list of corresponding uids. Keys are strings containing
        // the sdk or package name along with optional additional information based on opt.
        final Map<String, List<String>> out = new HashMap<>();
        for (int userId : userIds) {
            final int translatedUserId;
            try {
                translatedUserId =
                    translateUserId(userId, UserHandle.USER_SYSTEM, "runListPackages");
            } catch (RuntimeException ex) {
                getErrPrintWriter().println("Error: " + ex.toString());
                continue;
            }
            @SuppressWarnings("unchecked") final ParceledListSlice<PackageInfo> slice =
                    mInterface.getInstalledPackages(getFlags, translatedUserId);
            final List<PackageInfo> packages = slice.getList();

            final int count = packages.size();
            for (int p = 0; p < count; p++) {
                final PackageInfo info = packages.get(p);
                final StringBuilder stringBuilder = new StringBuilder();
                if (filter != null && !info.packageName.contains(filter)) {
                    continue;
                }
                final boolean isApex = info.isApex;
                if (uid != -1 && !isApex && info.applicationInfo.uid != uid) {
                    continue;
                }

                final boolean isSystem = !isApex
                        && (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                final boolean isEnabled = !isApex && info.applicationInfo.enabled;
                if ((listDisabled && isEnabled)
                        || (listEnabled && !isEnabled)
                        || (listSystem && !isSystem)
                        || (listThirdParty && isSystem)
                        || (listApexOnly && !isApex)) {
                    continue;
                }
                if (listQuarantinedOnly && !mInterface.isPackageQuarantinedForUser(info.packageName,
                        translatedUserId)) {
                    continue;
                }

                String name = null;
                if (showSdks) {
                    final ParceledListSlice<SharedLibraryInfo> libsSlice =
                            mInterface.getDeclaredSharedLibraries(
                                info.packageName, getFlags, userId
                            );
                    if (libsSlice == null) {
                        continue;
                    }
                    final List<SharedLibraryInfo> libs = libsSlice.getList();
                    for (int l = 0, lsize = libs.size(); l < lsize; ++l) {
                        SharedLibraryInfo lib = libs.get(l);
                        if (lib.getType() == SharedLibraryInfo.TYPE_SDK_PACKAGE) {
                            name = lib.getName() + ":" + lib.getLongVersion();
                            break;
                        }
                    }
                    if (name == null) {
                        continue;
                    }
                } else {
                    name = info.packageName;
                }

                stringBuilder.append(prefix);
                if (showSourceDir) {
                    stringBuilder.append(info.applicationInfo.sourceDir);
                    stringBuilder.append("=");
                }
                stringBuilder.append(name);
                if (showVersionCode) {
                    stringBuilder.append(" versionCode:");
                    if (info.applicationInfo != null) {
                        stringBuilder.append(info.applicationInfo.longVersionCode);
                    } else {
                        stringBuilder.append(info.getLongVersionCode());
                    }
                }
                if (showStopped) {
                    stringBuilder.append(" stopped=");
                    stringBuilder.append(
                            ((info.applicationInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0)
                            ? "true" : "false");
                }
                if (listInstaller) {
                    stringBuilder.append("  installer=");
                    stringBuilder.append(mInterface.getInstallerPackageName(info.packageName));
                }
                List<String> uids = out.computeIfAbsent(
                        stringBuilder.toString(), k -> new ArrayList<>()
                );
                if (showUid && !isApex) {
                    uids.add(String.valueOf(info.applicationInfo.uid));
                }
            }
        }
        for (Map.Entry<String, List<String>> entry : out.entrySet()) {
            pw.print(entry.getKey());
            List<String> uids = entry.getValue();
            if (!uids.isEmpty()) {
                pw.print(" uid:");
                pw.print(String.join(",", uids));
            }
            pw.println();
        }
        return 0;
    }

    private int runListPermissionGroups() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final List<PermissionGroupInfo> pgs = mPermissionManager.getAllPermissionGroups(0);

        final int count = pgs.size();
        for (int p = 0; p < count ; p++) {
            final PermissionGroupInfo pgi = pgs.get(p);
            pw.print("permission group:");
            pw.println(pgi.name);
        }
        return 0;
    }

    private int runListPermissions() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        boolean labels = false;
        boolean groups = false;
        boolean userOnly = false;
        boolean summary = false;
        boolean dangerousOnly = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-d":
                    dangerousOnly = true;
                    break;
                case "-f":
                    labels = true;
                    break;
                case "-g":
                    groups = true;
                    break;
                case "-s":
                    groups = true;
                    labels = true;
                    summary = true;
                    break;
                case "-u":
                    userOnly = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final ArrayList<String> groupList = new ArrayList<String>();
        if (groups) {
            final List<PermissionGroupInfo> infos =
                    mPermissionManager.getAllPermissionGroups(0 /*flags*/);
            final int count = infos.size();
            for (int i = 0; i < count; i++) {
                groupList.add(infos.get(i).name);
            }
            groupList.add(null);
        } else {
            final String grp = getNextArg();
            groupList.add(grp);
        }

        if (dangerousOnly) {
            pw.println("Dangerous Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    PermissionInfo.PROTECTION_DANGEROUS,
                    PermissionInfo.PROTECTION_DANGEROUS);
            if (userOnly) {
                pw.println("Normal Permissions:");
                pw.println("");
                doListPermissions(groupList, groups, labels, summary,
                        PermissionInfo.PROTECTION_NORMAL,
                        PermissionInfo.PROTECTION_NORMAL);
            }
        } else if (userOnly) {
            pw.println("Dangerous and Normal Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    PermissionInfo.PROTECTION_NORMAL,
                    PermissionInfo.PROTECTION_DANGEROUS);
        } else {
            pw.println("All Permissions:");
            pw.println("");
            doListPermissions(groupList, groups, labels, summary,
                    -10000, 10000);
        }
        return 0;
    }

    private static class SessionDump {
        boolean onlyParent; // Show parent sessions only
        boolean onlyReady; // Show only staged sessions that are in ready state
        boolean onlySessionId; // Show sessionId only
    }

    // Returns true if the provided flag is a session flag and given SessionDump was updated
    private boolean setSessionFlag(String flag, SessionDump sessionDump) {
        switch (flag) {
            case "--only-parent":
                sessionDump.onlyParent = true;
                break;
            case "--only-ready":
                sessionDump.onlyReady = true;
                break;
            case "--only-sessionid":
                sessionDump.onlySessionId = true;
                break;
            default:
                return false;
        }
        return true;
    }

    private int runListStagedSessions() {
        try (IndentingPrintWriter pw = new IndentingPrintWriter(
                getOutPrintWriter(), /* singleIndent */ "  ", /* wrapLength */ 120)) {
            final SessionDump sessionDump = new SessionDump();
            String opt;
            while ((opt = getNextOption()) != null) {
                if (!setSessionFlag(opt, sessionDump)) {
                    pw.println("Error: Unknown option: " + opt);
                    return -1;
                }
            }

            try {
                final List<SessionInfo> stagedSessions =
                        mInterface.getPackageInstaller().getStagedSessions().getList();
                printSessionList(pw, stagedSessions, sessionDump);
            } catch (RemoteException e) {
                pw.println("Failure ["
                        + e.getClass().getName() + " - "
                        + e.getMessage() + "]");
                return -1;
            }
            return 1;
        }
    }

    private void printSessionList(IndentingPrintWriter pw, List<SessionInfo> stagedSessions,
            SessionDump sessionDump) {
        final SparseArray<SessionInfo> sessionById = new SparseArray<>(stagedSessions.size());
        for (SessionInfo session : stagedSessions) {
            sessionById.put(session.getSessionId(), session);
        }
        for (SessionInfo session: stagedSessions) {
            if (sessionDump.onlyReady && !session.isStagedSessionReady()) {
                continue;
            }
            if (session.getParentSessionId() != SessionInfo.INVALID_ID) {
                continue;
            }
            printSession(pw, session, sessionDump);
            if (session.isMultiPackage() && !sessionDump.onlyParent) {
                pw.increaseIndent();
                final int[] childIds = session.getChildSessionIds();
                for (int i = 0; i < childIds.length; i++) {
                    final SessionInfo childSession = sessionById.get(childIds[i]);
                    if (childSession == null) {
                        if (sessionDump.onlySessionId) {
                            pw.println(childIds[i]);
                        } else {
                            pw.println("sessionId = " + childIds[i] + "; not found");
                        }
                    } else {
                        printSession(pw, childSession, sessionDump);
                    }
                }
                pw.decreaseIndent();
            }
        }
    }

    private static void printSession(PrintWriter pw, SessionInfo session, SessionDump sessionDump) {
        if (sessionDump.onlySessionId) {
            pw.println(session.getSessionId());
            return;
        }
        pw.println("sessionId = " + session.getSessionId()
                + "; appPackageName = " + session.getAppPackageName()
                + "; isStaged = " + session.isStaged()
                + "; isReady = " + session.isStagedSessionReady()
                + "; isApplied = " + session.isStagedSessionApplied()
                + "; isFailed = " + session.isStagedSessionFailed()
                + "; errorMsg = " + session.getStagedSessionErrorMessage()
                + ";");
    }

    private Intent parseIntentAndUser() throws URISyntaxException {
        mTargetUser = UserHandle.USER_CURRENT;
        mBrief = false;
        mComponents = false;
        Intent intent = Intent.parseCommandArgs(this, new Intent.CommandOptionHandler() {
            @Override
            public boolean handleOption(String opt, ShellCommand cmd) {
                if ("--user".equals(opt)) {
                    mTargetUser = UserHandle.parseUserArg(cmd.getNextArgRequired());
                    return true;
                } else if ("--brief".equals(opt)) {
                    mBrief = true;
                    return true;
                } else if ("--components".equals(opt)) {
                    mComponents = true;
                    return true;
                } else if ("--query-flags".equals(opt)) {
                    mQueryFlags = Integer.decode(cmd.getNextArgRequired());
                    return true;
                }
                return false;
            }
        });
        mTargetUser = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), mTargetUser, false, false, null, null);
        return intent;
    }

    private void printResolveInfo(PrintWriterPrinter pr, String prefix, ResolveInfo ri,
            boolean brief, boolean components) {
        if (brief || components) {
            final ComponentName comp;
            if (ri.activityInfo != null) {
                comp = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            } else if (ri.serviceInfo != null) {
                comp = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
            } else if (ri.providerInfo != null) {
                comp = new ComponentName(ri.providerInfo.packageName, ri.providerInfo.name);
            } else {
                comp = null;
            }
            if (comp != null) {
                if (!components) {
                    pr.println(prefix + "priority=" + ri.priority
                            + " preferredOrder=" + ri.preferredOrder
                            + " match=0x" + Integer.toHexString(ri.match)
                            + " specificIndex=" + ri.specificIndex
                            + " isDefault=" + ri.isDefault);
                }
                pr.println(prefix + comp.flattenToShortString());
                return;
            }
        }
        ri.dump(pr, prefix);
    }

    private int runResolveActivity() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            ResolveInfo ri = mInterface.resolveIntent(intent, intent.getType(), mQueryFlags,
                    mTargetUser);
            PrintWriter pw = getOutPrintWriter();
            if (ri == null) {
                pw.println("No activity found");
            } else {
                PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                printResolveInfo(pr, "", ri, mBrief, mComponents);
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runQueryIntentActivities() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            List<ResolveInfo> result = mInterface.queryIntentActivities(intent, intent.getType(),
                    mQueryFlags, mTargetUser).getList();
            PrintWriter pw = getOutPrintWriter();
            if (result == null || result.size() <= 0) {
                pw.println("No activities found");
            } else {
                if (!mComponents) {
                    pw.print(result.size()); pw.println(" activities found:");
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        pw.print("  Activity #"); pw.print(i); pw.println(":");
                        printResolveInfo(pr, "    ", result.get(i), mBrief, mComponents);
                    }
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        printResolveInfo(pr, "", result.get(i), mBrief, mComponents);
                    }
                }
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runQueryIntentServices() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            List<ResolveInfo> result = mInterface.queryIntentServices(intent, intent.getType(),
                    mQueryFlags, mTargetUser).getList();
            PrintWriter pw = getOutPrintWriter();
            if (result == null || result.size() <= 0) {
                pw.println("No services found");
            } else {
                if (!mComponents) {
                    pw.print(result.size()); pw.println(" services found:");
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        pw.print("  Service #"); pw.print(i); pw.println(":");
                        printResolveInfo(pr, "    ", result.get(i), mBrief, mComponents);
                    }
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        printResolveInfo(pr, "", result.get(i), mBrief, mComponents);
                    }
                }
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runQueryIntentReceivers() {
        Intent intent;
        try {
            intent = parseIntentAndUser();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        try {
            List<ResolveInfo> result = mInterface.queryIntentReceivers(intent, intent.getType(),
                    mQueryFlags, mTargetUser).getList();
            PrintWriter pw = getOutPrintWriter();
            if (result == null || result.size() <= 0) {
                pw.println("No receivers found");
            } else {
                if (!mComponents) {
                    pw.print(result.size()); pw.println(" receivers found:");
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        pw.print("  Receiver #"); pw.print(i); pw.println(":");
                        printResolveInfo(pr, "    ", result.get(i), mBrief, mComponents);
                    }
                } else {
                    PrintWriterPrinter pr = new PrintWriterPrinter(pw);
                    for (int i = 0; i < result.size(); i++) {
                        printResolveInfo(pr, "", result.get(i), mBrief, mComponents);
                    }
                }
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Failed calling service", e);
        }
        return 0;
    }

    private int runStreamingInstall() throws RemoteException {
        final InstallParams params = makeInstallParams(UNSUPPORTED_INSTALL_CMD_OPTS);
        if (params.sessionParams.dataLoaderParams == null) {
            params.sessionParams.setDataLoaderParams(
                    PackageManagerShellCommandDataLoader.getStreamingDataLoaderParams(this));
        }
        return doRunInstall(params);
    }

    private int runArchivedInstall() throws RemoteException {
        final InstallParams params = makeInstallParams(UNSUPPORTED_INSTALL_CMD_OPTS);
        params.sessionParams.installFlags |= PackageManager.INSTALL_ARCHIVED;
        if (params.sessionParams.dataLoaderParams == null) {
            params.sessionParams.setDataLoaderParams(
                    PackageManagerShellCommandDataLoader.getStreamingDataLoaderParams(this));
        }
        return doRunInstall(params);
    }

    private int runIncrementalInstall() throws RemoteException {
        final InstallParams params = makeInstallParams(UNSUPPORTED_INSTALL_CMD_OPTS);
        if (params.sessionParams.dataLoaderParams == null) {
            params.sessionParams.setDataLoaderParams(
                    PackageManagerShellCommandDataLoader.getIncrementalDataLoaderParams(this));
        }
        return doRunInstall(params);
    }

    private int runInstall() throws RemoteException {
        return doRunInstall(makeInstallParams(UNSUPPORTED_INSTALL_CMD_OPTS));
    }

    private int doRunInstall(final InstallParams params) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();

        int requestUserId = params.userId;
        if (requestUserId != UserHandle.USER_ALL && requestUserId != UserHandle.USER_CURRENT) {
            UserManagerInternal umi =
                    LocalServices.getService(UserManagerInternal.class);
            UserInfo userInfo = umi.getUserInfo(requestUserId);
            if (userInfo == null) {
                pw.println("Failure [user " + requestUserId + " doesn't exist]");
                return 1;
            }
        }

        final boolean isStreaming = params.sessionParams.dataLoaderParams != null;
        final boolean isApex =
                (params.sessionParams.installFlags & PackageManager.INSTALL_APEX) != 0;
        final boolean installArchived =
                (params.sessionParams.installFlags & PackageManager.INSTALL_ARCHIVED) != 0;

        ArrayList<String> args = getRemainingArgs();

        final boolean fromStdIn = args.isEmpty() || STDIN_PATH.equals(args.get(0));
        final boolean hasSplits = args.size() > 1;

        if (fromStdIn && params.sessionParams.sizeBytes == -1) {
            pw.println("Error: must either specify a package size or an APK file");
            return 1;
        }

        if (isApex && hasSplits) {
            pw.println("Error: can't specify SPLIT(s) for APEX");
            return 1;
        }

        if (installArchived) {
            if (hasSplits) {
                pw.println("Error: can't have SPLIT(s) for Archival install");
                return 1;
            }
        }

        if (!isStreaming) {
            if (fromStdIn && hasSplits) {
                pw.println("Error: can't specify SPLIT(s) along with STDIN");
                return 1;
            }

            if (args.isEmpty()) {
                args.add(STDIN_PATH);
            } else {
                setParamsSize(params, args);
            }
        }

        final int sessionId = doCreateSession(params.sessionParams,
                params.installerPackageName, params.userId);
        boolean abandonSession = true;
        try {
            if (isStreaming) {
                if (doAddFiles(sessionId, args, params.sessionParams.sizeBytes, isApex,
                        installArchived) != PackageInstaller.STATUS_SUCCESS) {
                    return 1;
                }
            } else {
                if (doWriteSplits(sessionId, args, params.sessionParams.sizeBytes, isApex)
                        != PackageInstaller.STATUS_SUCCESS) {
                    return 1;
                }
            }
            if (doCommitSession(sessionId, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            abandonSession = false;

            if (params.sessionParams.isStaged && params.stagedReadyTimeoutMs > 0) {
                return doWaitForStagedSessionReady(sessionId, params.stagedReadyTimeoutMs, pw);
            }

            pw.println("Success");
            return 0;
        } finally {
            if (abandonSession) {
                try {
                    doAbandonSession(sessionId, false /*logSuccess*/);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private int doWaitForStagedSessionReady(int sessionId, long timeoutMs, PrintWriter pw)
              throws RemoteException {
        Preconditions.checkArgument(timeoutMs > 0);
        PackageInstaller.SessionInfo si = mInterface.getPackageInstaller()
                .getSessionInfo(sessionId);
        if (si == null) {
            pw.println("Failure [Unknown session " + sessionId + "]");
            return 1;
        }
        if (!si.isStaged()) {
            pw.println("Failure [Session " + sessionId + " is not a staged session]");
            return 1;
        }
        long currentTime = System.currentTimeMillis();
        long endTime = currentTime + timeoutMs;
        // Using a loop instead of BroadcastReceiver since we can receive session update
        // broadcast only if packageInstallerName is "android". We can't always force
        // "android" as packageIntallerName, e.g, rollback auto implies
        // "-i com.android.shell".
        while (si != null && currentTime < endTime) {
            if (si.isStagedSessionReady() || si.isStagedSessionFailed()) {
                break;
            }
            SystemClock.sleep(Math.min(endTime - currentTime, 100));
            currentTime = System.currentTimeMillis();
            si = mInterface.getPackageInstaller().getSessionInfo(sessionId);
        }
        if (si == null) {
            pw.println("Failure [failed to retrieve SessionInfo]");
            return 1;
        }
        if (!si.isStagedSessionReady() && !si.isStagedSessionFailed()) {
            pw.println("Failure [timed out after " + timeoutMs + " ms]");
            return 1;
        }
        if (!si.isStagedSessionReady()) {
            pw.println("Error [" + si.getStagedSessionErrorCode() + "] ["
                    + si.getStagedSessionErrorMessage() + "]");
            return 1;
        }
        pw.println("Success. Reboot device to apply staged session");
        return 0;
    }

    private int runInstallAbandon() throws RemoteException {
        final int sessionId = Integer.parseInt(getNextArg());
        return doAbandonSession(sessionId, true /*logSuccess*/);
    }

    private int runInstallCommit() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        String opt;
        long stagedReadyTimeoutMs = DEFAULT_STAGED_READY_TIMEOUT_MS;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--staged-ready-timeout":
                    stagedReadyTimeoutMs = Long.parseLong(getNextArgRequired());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }
        final int sessionId = Integer.parseInt(getNextArg());
        if (doCommitSession(sessionId, false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
            return 1;
        }
        final PackageInstaller.SessionInfo si = mInterface.getPackageInstaller()
                .getSessionInfo(sessionId);
        if (si != null && si.isStaged() && stagedReadyTimeoutMs > 0) {
            return doWaitForStagedSessionReady(sessionId, stagedReadyTimeoutMs, pw);
        }
        pw.println("Success");
        return 0;
    }

    private int runInstallCreate() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final InstallParams installParams = makeInstallParams(UNSUPPORTED_SESSION_CREATE_OPTS);
        final int sessionId = doCreateSession(installParams.sessionParams,
                installParams.installerPackageName, installParams.userId);

        // NOTE: adb depends on parsing this string
        pw.println("Success: created install session [" + sessionId + "]");
        return 0;
    }

    private int runInstallWrite() throws RemoteException {
        long sizeBytes = -1;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("-S")) {
                sizeBytes = Long.parseLong(getNextArg());
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }

        final int sessionId = Integer.parseInt(getNextArg());
        final String splitName = getNextArg();
        final String path = getNextArg();
        return doWriteSplit(sessionId, path, sizeBytes, splitName, true /*logSuccess*/);
    }

    private int runInstallAddSession() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int parentSessionId = Integer.parseInt(getNextArg());

        IntArray otherSessionIds = new IntArray();
        String opt;
        while ((opt = getNextArg()) != null) {
            otherSessionIds.add(Integer.parseInt(opt));
        }
        if (otherSessionIds.size() == 0) {
            pw.println("Error: At least two sessions are required.");
            return 1;
        }
        return doInstallAddSession(parentSessionId, otherSessionIds.toArray(),
                true /*logSuccess*/);
    }

    private int runInstallSetPreVerifiedDomains() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int sessionId = Integer.parseInt(getNextArg());
        final String preVerifiedDomainsStr = getNextArg();
        final String[] preVerifiedDomains = preVerifiedDomainsStr.split(",");
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            session.setPreVerifiedDomains(new ArraySet<>(preVerifiedDomains));
        } finally {
            IoUtils.closeQuietly(session);
        }
        return 0;
    }

    private int runInstallGetPreVerifiedDomains() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int sessionId = Integer.parseInt(getNextArg());
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            Set<String> preVerifiedDomains = session.getPreVerifiedDomains();
            if (preVerifiedDomains.isEmpty()) {
                pw.println("The session doesn't have any pre-verified domains specified.");
            } else {
                pw.println(String.join(",", preVerifiedDomains));
            }
        } finally {
            IoUtils.closeQuietly(session);
        }
        return 0;
    }

    private int runInstallRemove() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();

        final int sessionId = Integer.parseInt(getNextArg());

        ArrayList<String> splitNames = getRemainingArgs();
        if (splitNames.isEmpty()) {
            pw.println("Error: split name not specified");
            return 1;
        }
        return doRemoveSplits(sessionId, splitNames, true /*logSuccess*/);
    }

    private int runGetArchivedPackageMetadata() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }
        final int translatedUserId = translateUserId(userId, UserHandle.USER_NULL,
                "runGetArchivedPackageMetadata");

        try {
            var archivedPackage = mInterface.getArchivedPackage(packageName, translatedUserId);
            if (archivedPackage == null) {
                pw.write("Package not found " + packageName);
                return -1;
            }

            Parcel parcel = Parcel.obtain();
            byte[] bytes;
            try {
                parcel.writeParcelable(archivedPackage, 0);
                bytes = parcel.marshall();
            } finally {
                parcel.recycle();
            }

            String encoded = HexEncoding.encodeToString(bytes);
            pw.write(encoded);
        } catch (Exception e) {
            getErrPrintWriter().println("Failed to get archived package, reason: " + e);
            pw.println("Failure [failed to get archived package], reason: " + e);
            return -1;
        }
        return 0;
    }

    /**
     * Returns a string that shows the number of bytes in b, Kb, Mb or Gb.
     */
    protected static String getFormattedBytes(long size) {
        double k = size/1024.0;
        double m = size/1048576.0;
        double g = size/1073741824.0;

        DecimalFormat dec = new DecimalFormat("0.00");
        if (g > 1) {
            return dec.format(g).concat(" Gb");
        } else if (m > 1) {
            return dec.format(m).concat(" Mb");
        } else if (k > 1) {
            return dec.format(k).concat(" Kb");
        }
        return "";
    }

    /**
     * Return the string that displays the data size.
     */
    private String getDataSizeDisplay(long size) {
        String formattedOutput = getFormattedBytes(size);
        if (!formattedOutput.isEmpty()) {
           formattedOutput = " (" + formattedOutput + ")";
        }
        return Long.toString(size) + " bytes" + formattedOutput;
    }

    /**
     * Display storage stats of the specified package.
     *
     * Usage: get-package-storage-stats [--usr USER_ID] PACKAGE
     */
    private int runGetPackageStorageStats() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        if (!android.content.pm.Flags.getPackageStorageStats()) {
            pw.println("Error: get_package_storage_stats flag is not enabled");
            return 1;
        }
        if (!android.app.usage.Flags.getAppBytesByDataTypeApi()) {
            pw.println("Error: get_app_bytes_by_data_type_api flag is not enabled");
            return 1;
        }
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }
        try {
            StorageStatsManager storageStatsManager =
                mContext.getSystemService(StorageStatsManager.class);
            final int translatedUserId = translateUserId(userId, UserHandle.USER_NULL,
                "runGetPackageStorageStats");
            StorageStats stats =
                storageStatsManager.queryStatsForPackage(StorageManager.UUID_DEFAULT,
                    packageName, UserHandle.of(translatedUserId));

            pw.println("code: " + getDataSizeDisplay(stats.getAppBytes()));
            pw.println("data: " + getDataSizeDisplay(stats.getDataBytes()));
            pw.println("cache: " + getDataSizeDisplay(stats.getCacheBytes()));
            pw.println("apk: " + getDataSizeDisplay(stats.getAppBytesByDataType(
                StorageStats.APP_DATA_TYPE_FILE_TYPE_APK)));
            pw.println("lib: " + getDataSizeDisplay(
                stats.getAppBytesByDataType(StorageStats.APP_DATA_TYPE_LIB)));
            pw.println("dm: " + getDataSizeDisplay(stats.getAppBytesByDataType(
                StorageStats.APP_DATA_TYPE_FILE_TYPE_DM)));
            pw.println("dexopt artifacts: " + getDataSizeDisplay(stats.getAppBytesByDataType(
                StorageStats.APP_DATA_TYPE_FILE_TYPE_DEXOPT_ARTIFACT)));
            pw.println("current profile : " + getDataSizeDisplay(stats.getAppBytesByDataType(
                StorageStats.APP_DATA_TYPE_FILE_TYPE_CURRENT_PROFILE)));
            pw.println("reference profile: " + getDataSizeDisplay(stats.getAppBytesByDataType(
                StorageStats.APP_DATA_TYPE_FILE_TYPE_REFERENCE_PROFILE)));
            pw.println("external cache: " + getDataSizeDisplay(stats.getExternalCacheBytes()));
        } catch (Exception e) {
            getErrPrintWriter().println("Failed to get storage stats, reason: " + e);
            pw.println("Failure [failed to get storage stats], reason: " + e);
            return -1;
        }
        return 0;
    }

    private int runInstallExisting() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_CURRENT;
        int installFlags = PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
        String opt;
        boolean waitTillComplete = false;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--ephemeral":
                case "--instant":
                    installFlags |= PackageManager.INSTALL_INSTANT_APP;
                    installFlags &= ~PackageManager.INSTALL_FULL_APP;
                    break;
                case "--full":
                    installFlags &= ~PackageManager.INSTALL_INSTANT_APP;
                    installFlags |= PackageManager.INSTALL_FULL_APP;
                    break;
                case "--wait":
                    waitTillComplete = true;
                    break;
                case "--restrict-permissions":
                    installFlags &= ~PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runInstallExisting");

        int installReason = PackageManager.INSTALL_REASON_UNKNOWN;
        try {
            if (waitTillComplete) {
                final LocalIntentReceiver receiver = new LocalIntentReceiver();
                final IPackageInstaller installer = mInterface.getPackageInstaller();
                pw.println("Installing package " + packageName + " for user: " + translatedUserId);
                installer.installExistingPackage(packageName, installFlags, installReason,
                        receiver.getIntentSender(), translatedUserId, null);
                final Intent result = receiver.getResult();
                final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                pw.println("Received intent for package install");
                return status == PackageInstaller.STATUS_SUCCESS ? 0 : 1;
            }

            final int res = mInterface.installExistingPackageAsUser(packageName, translatedUserId,
                    installFlags, installReason, null);
            if (res == PackageManager.INSTALL_FAILED_INVALID_URI) {
                throw new NameNotFoundException("Package " + packageName + " doesn't exist");
            }
            pw.println("Package " + packageName + " installed for user: " + translatedUserId);
            return 0;
        } catch (RemoteException | NameNotFoundException e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runSetInstallLocation() throws RemoteException {
        int loc;

        String arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: no install location specified.");
            return 1;
        }
        try {
            loc = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: install location has to be a number.");
            return 1;
        }
        if (!mInterface.setInstallLocation(loc)) {
            getErrPrintWriter().println("Error: install location has to be a number.");
            return 1;
        }
        return 0;
    }

    private int runGetInstallLocation() throws RemoteException {
        int loc = mInterface.getInstallLocation();
        String locStr = "invalid";
        if (loc == InstallLocationUtils.APP_INSTALL_AUTO) {
            locStr = "auto";
        } else if (loc == InstallLocationUtils.APP_INSTALL_INTERNAL) {
            locStr = "internal";
        } else if (loc == InstallLocationUtils.APP_INSTALL_EXTERNAL) {
            locStr = "external";
        }
        getOutPrintWriter().println(loc + "[" + locStr + "]");
        return 0;
    }

    public int runMovePackage() throws RemoteException {
        final String packageName = getNextArg();
        if (packageName == null) {
            getErrPrintWriter().println("Error: package name not specified");
            return 1;
        }
        String volumeUuid = getNextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }

        final int moveId = mInterface.movePackage(packageName, volumeUuid);

        int status = mInterface.getMoveStatus(moveId);
        while (!PackageManager.isMoveStatusFinished(status)) {
            SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);
            status = mInterface.getMoveStatus(moveId);
        }

        if (status == PackageManager.MOVE_SUCCEEDED) {
            getOutPrintWriter().println("Success");
            return 0;
        } else {
            getErrPrintWriter().println("Failure [" + status + "]");
            return 1;
        }
    }

    public int runMovePrimaryStorage() throws RemoteException {
        String volumeUuid = getNextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }

        final int moveId = mInterface.movePrimaryStorage(volumeUuid);

        int status = mInterface.getMoveStatus(moveId);
        while (!PackageManager.isMoveStatusFinished(status)) {
            SystemClock.sleep(DateUtils.SECOND_IN_MILLIS);
            status = mInterface.getMoveStatus(moveId);
        }

        if (status == PackageManager.MOVE_SUCCEEDED) {
            getOutPrintWriter().println("Success");
            return 0;
        } else {
            getErrPrintWriter().println("Failure [" + status + "]");
            return 1;
        }
    }

    private ArrayList<String> getRemainingArgs() {
        ArrayList<String> args = new ArrayList<>();
        String arg;
        while ((arg = getNextArg()) != null) {
            args.add(arg);
        }
        return args;
    }

    private static class SnapshotRuntimeProfileCallback
            extends ISnapshotRuntimeProfileCallback.Stub {
        private boolean mSuccess = false;
        private int mErrCode = -1;
        private ParcelFileDescriptor mProfileReadFd = null;
        private final CountDownLatch mDoneSignal = new CountDownLatch(1);

        @Override
        public void onSuccess(ParcelFileDescriptor profileReadFd) {
            mSuccess = true;
            try {
                // We need to dup the descriptor. We are in the same process as system server
                // and we will be receiving the same object (which will be closed on the
                // server side).
                mProfileReadFd = profileReadFd.dup();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mDoneSignal.countDown();
        }

        @Override
        public void onError(int errCode) {
            mSuccess = false;
            mErrCode = errCode;
            mDoneSignal.countDown();
        }

        boolean waitTillDone() {
            boolean done = false;
            try {
                // The time-out is an arbitrary large value. Since this is a local call the result
                // will come very fast.
                done = mDoneSignal.await(10000000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            return done && mSuccess;
        }
    }

    private int runUninstall() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int flags = 0;
        int userId = UserHandle.USER_ALL;
        long versionCode = PackageManager.VERSION_CODE_HIGHEST;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "-k":
                    flags |= PackageManager.DELETE_KEEP_DATA;
                    break;
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) {
                        UserManagerInternal umi =
                                LocalServices.getService(UserManagerInternal.class);
                        UserInfo userInfo = umi.getUserInfo(userId);
                        if (userInfo == null) {
                            pw.println("Failure [user " + userId + " doesn't exist]");
                            return 1;
                        }
                    }
                    break;
                case "--versionCode":
                    versionCode = Long.parseLong(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        // if a split is specified, just remove it and not the whole package
        ArrayList<String> splitNames = getRemainingArgs();
        if (!splitNames.isEmpty()) {
            return runRemoveSplits(packageName, splitNames);
        }

        if (userId == UserHandle.USER_ALL) {
            flags |= PackageManager.DELETE_ALL_USERS;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "runUninstall");
        final LocalIntentReceiver receiver = new LocalIntentReceiver();
        final PackageManagerInternal internal =
                LocalServices.getService(PackageManagerInternal.class);

        if (internal.isApexPackage(packageName)) {
            internal.uninstallApex(
                    packageName, versionCode, translatedUserId, receiver.getIntentSender(), flags);
        } else {
            if ((flags & PackageManager.DELETE_ALL_USERS) == 0) {
                final PackageInfo info = mInterface.getPackageInfo(packageName,
                        PackageManager.MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, translatedUserId);
                if (info == null) {
                    pw.println("Failure [not installed for " + translatedUserId + "]");
                    return 1;
                }
                final boolean isSystem =
                        (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                // If we are being asked to delete a system app for just one
                // user set flag so it disables rather than reverting to system
                // version of the app.
                if (isSystem) {
                    flags |= PackageManager.DELETE_SYSTEM_APP;
                }
            }
            mInterface.getPackageInstaller().uninstall(new VersionedPackage(packageName,
                            versionCode), null /*callerPackageName*/, flags,
                    receiver.getIntentSender(), translatedUserId);
        }

        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            pw.println("Success");
            return 0;
        } else {
            pw.println("Failure ["
                    + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            return 1;
        }
    }

    private int runRemoveSplits(String packageName, Collection<String> splitNames)
            throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final SessionParams sessionParams = new SessionParams(SessionParams.MODE_INHERIT_EXISTING);
        sessionParams.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
        sessionParams.appPackageName = packageName;
        final int sessionId =
                doCreateSession(sessionParams, null /*installerPackageName*/, UserHandle.USER_ALL);
        boolean abandonSession = true;
        try {
            if (doRemoveSplits(sessionId, splitNames, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            if (doCommitSession(sessionId, false /*logSuccess*/)
                    != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
            abandonSession = false;
            pw.println("Success");
            return 0;
        } finally {
            if (abandonSession) {
                try {
                    doAbandonSession(sessionId, false /*logSuccess*/);
                } catch (RuntimeException ignore) {
                }
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

    private int runClear() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        boolean cacheOnly = false;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--cache-only":
                    cacheOnly = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }

        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runClear");
        final ClearDataObserver obs = new ClearDataObserver();
        if (!cacheOnly) {
            ActivityManager.getService()
                    .clearApplicationUserData(pkg, false, obs, translatedUserId);
        } else {
            mInterface.deleteApplicationCacheFilesAsUser(pkg, translatedUserId, obs);
        }
        synchronized (obs) {
            while (!obs.finished) {
                try {
                    obs.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        if (obs.result) {
            getOutPrintWriter().println("Success");
            return 0;
        } else {
            getErrPrintWriter().println("Failed");
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

    private int runSetEnabledSetting(int state) throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }

        final String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package or component specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runSetEnabledSetting");
        final ComponentName cn = ComponentName.unflattenFromString(pkg);
        if (cn == null) {
            mInterface.setApplicationEnabledSetting(pkg, state, 0, translatedUserId,
                    "shell:" + android.os.Process.myUid());
            getOutPrintWriter().println("Package " + pkg + " new state: "
                    + enabledSettingToString(
                    mInterface.getApplicationEnabledSetting(pkg, translatedUserId)));
            return 0;
        } else {
            mInterface.setComponentEnabledSetting(cn, state, 0, translatedUserId, "shell");
            getOutPrintWriter().println("Component " + cn.toShortString() + " new state: "
                    + enabledSettingToString(
                    mInterface.getComponentEnabledSetting(cn, translatedUserId)));
            return 0;
        }
    }

    private int runSetHiddenSetting(boolean state) throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }

        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package or component specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runSetHiddenSetting");
        mInterface.setApplicationHiddenSettingAsUser(pkg, state, translatedUserId);
        getOutPrintWriter().println("Package " + pkg + " new hidden state: "
                + mInterface.getApplicationHiddenSettingAsUser(pkg, translatedUserId));
        return 0;
    }

    private int runSetStoppedState(boolean state) throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;
        String option = getNextOption();
        if (option != null && option.equals("--user")) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }

        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runSetStoppedState");
        mInterface.setPackageStoppedState(pkg, state, translatedUserId);
        getOutPrintWriter().println("Package " + pkg + " new stopped state: "
                + mInterface.isPackageStoppedForUser(pkg, translatedUserId));
        return 0;
    }

    private int runSetDistractingRestriction() {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        String opt;
        int flags = 0;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--flag":
                    final String flag = getNextArgRequired();
                    switch (flag) {
                        case "hide-notifications":
                            flags |= PackageManager.RESTRICTION_HIDE_NOTIFICATIONS;
                            break;
                        case "hide-from-suggestions":
                            flags |= PackageManager.RESTRICTION_HIDE_FROM_SUGGESTIONS;
                            break;
                        default:
                            pw.println("Unrecognized flag: " + flag);
                            return 1;
                    }
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final List<String> packageNames = getRemainingArgs();
        if (packageNames.isEmpty()) {
            pw.println("Error: package name not specified");
            return 1;
        }
        try {
            final int translatedUserId = translateUserId(userId, UserHandle.USER_NULL,
                    "set-distracting");
            final String[] errored = mInterface.setDistractingPackageRestrictionsAsUser(
                    packageNames.toArray(new String[]{}), flags, translatedUserId);
            if (errored.length > 0) {
                pw.println("Could not set restriction for: " + Arrays.toString(errored));
                return 1;
            }
            return 0;
        } catch (RemoteException | IllegalArgumentException e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runGetDistractingRestriction() {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final List<String> packageNames = getRemainingArgs();
        if (packageNames.isEmpty()) {
            pw.println("Error: package name not specified");
            return 1;
        }
        pw.println("Distracting restrictions state for user " + userId);

        final int translatedUserId = translateUserId(userId, UserHandle.USER_NULL,
                "get-distracting");
        final String[] packages = packageNames.toArray(new String[]{});
        int[] res = mPm.getDistractingPackageRestrictionsAsUser(packages, translatedUserId);

        for (int i = 0; i < res.length; i++) {
            final int state = res[i];
            if (state == -1) {
                pw.println(packages[i] + " not found ...");
            } else {
                pw.println(packages[i] + "  state: " + stateToString(state));
            }
        }

        return 0;
    }

    private static String stateToString(@PackageManager.DistractionRestriction int flag) {
        switch (flag) {
            case RESTRICTION_NONE:
                return "NONE";
            case RESTRICTION_HIDE_FROM_SUGGESTIONS:
                return "HIDE_FROM_SUGGESTIONS";
            case RESTRICTION_HIDE_NOTIFICATIONS:
                return "HIDE_NOTIFICATIONS";
            default:
                return "UNKNOWN";
        }
    }

    private int runSuspend(boolean suspendedState, int flags) {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        String dialogMessage = null;
        final PersistableBundle appExtras = new PersistableBundle();
        final PersistableBundle launcherExtras = new PersistableBundle();
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--dialogMessage":
                    dialogMessage = getNextArgRequired();
                    break;
                case "--ael":
                case "--aes":
                case "--aed":
                case "--lel":
                case "--les":
                case "--led":
                    final String key = getNextArgRequired();
                    final String val = getNextArgRequired();
                    if (!suspendedState) {
                        break;
                    }
                    final PersistableBundle bundleToInsert =
                            opt.startsWith("--a") ? appExtras : launcherExtras;
                    switch (opt.charAt(4)) {
                        case 'l':
                            bundleToInsert.putLong(key, Long.valueOf(val));
                            break;
                        case 'd':
                            bundleToInsert.putDouble(key, Double.valueOf(val));
                            break;
                        case 's':
                            bundleToInsert.putString(key, val);
                            break;
                    }
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        final List<String> packageNames = getRemainingArgs();
        if (packageNames.isEmpty()) {
            pw.println("Error: package name not specified");
            return 1;
        }
        final String callingPackage =
                (Binder.getCallingUid() == Process.ROOT_UID) ? "root" : "com.android.shell";

        final SuspendDialogInfo info;
        if (!TextUtils.isEmpty(dialogMessage)) {
            info = new SuspendDialogInfo.Builder()
                    .setMessage(dialogMessage)
                    .build();
        } else {
            info = null;
        }
        try {
            final int translatedUserId =
                    translateUserId(userId, UserHandle.USER_NULL, "runSuspend");
            mInterface.setPackagesSuspendedAsUser(packageNames.toArray(new String[] {}),
                    suspendedState, ((appExtras.size() > 0) ? appExtras : null),
                    ((launcherExtras.size() > 0) ? launcherExtras : null),
                    info, flags, callingPackage, UserHandle.USER_SYSTEM, translatedUserId);
            for (int i = 0; i < packageNames.size(); i++) {
                final String packageName = packageNames.get(i);
                pw.println("Package " + packageName + " new suspended state: "
                        + mInterface.isPackageSuspendedForUser(packageName, translatedUserId));
            }
            return 0;
        } catch (RemoteException | IllegalArgumentException e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runGrantRevokePermission(boolean grant) throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;

        String opt;
        boolean allPermissions = false;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            }
            if (opt.equals("--all-permissions")) {
                allPermissions = true;
            }
        }

        String pkg = getNextArg();
        if (!allPermissions && pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        String perm = getNextArg();
        if (!allPermissions && perm == null) {
            getErrPrintWriter().println("Error: no permission specified");
            return 1;
        }
        if (allPermissions && perm != null) {
            getErrPrintWriter().println("Error: permission specified but not expected");
            return 1;
        }
        final UserHandle translatedUser = UserHandle.of(translateUserId(userId,
                UserHandle.USER_NULL, "runGrantRevokePermission"));

        List<PackageInfo> packageInfos;
        PackageManager pm = mContext.createContextAsUser(translatedUser, 0).getPackageManager();
        if (pkg == null) {
            packageInfos = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } else {
            try {
                packageInfos = Collections.singletonList(pm.getPackageInfo(pkg,
                        PackageManager.GET_PERMISSIONS));
            } catch (NameNotFoundException e) {
                getErrPrintWriter().println("Error: package not found");
                getOutPrintWriter().println("Failure [package not found]");
                return 1;
            }
        }

        for (PackageInfo packageInfo : packageInfos) {
            List<String> permissions = Collections.singletonList(perm);
            if (allPermissions) {
                permissions = getRequestedRuntimePermissions(packageInfo);
            }
            for (String permission : permissions) {
                if (grant) {
                    try {
                        mPermissionManager.grantRuntimePermission(packageInfo.packageName,
                                permission,
                                translatedUser);
                    } catch (Exception e) {
                        if (!allPermissions) {
                            throw e;
                        } else {
                            Slog.w(TAG, "Could not grant permission " + permission, e);
                        }
                    }
                } else {
                    try {
                        mPermissionManager.revokeRuntimePermission(packageInfo.packageName,
                                permission,
                                translatedUser, null);
                    } catch (Exception e) {
                        if (!allPermissions) {
                            throw e;
                        } else {
                            Slog.w(TAG, "Could not grant permission " + permission, e);
                        }
                    }
                }
            }
        }
        return 0;
    }

    private List<String> getRequestedRuntimePermissions(PackageInfo info) {
        // No requested permissions
        if (info.requestedPermissions == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        PackageManager pm = mContext.getPackageManager();
        // Iterate through requested permissions for denied ones
        for (String permission : info.requestedPermissions) {
            PermissionInfo pi = null;
            try {
                pi = pm.getPermissionInfo(permission, 0);
            } catch (NameNotFoundException nnfe) {
                // ignore
            }
            if (pi == null) {
                continue;
            }
            if (pi.getProtection() != PermissionInfo.PROTECTION_DANGEROUS) {
                continue;
            }
            result.add(permission);
        }
        return result;
    }

    private int runResetPermissions() throws RemoteException {
        mLegacyPermissionManager.resetRuntimePermissions();
        return 0;
    }

    private int setOrClearPermissionFlags(boolean setFlags) {
        int userId = UserHandle.USER_SYSTEM;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            }
        }

        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        String perm = getNextArg();
        if (perm == null) {
            getErrPrintWriter().println("Error: no permission specified");
            return 1;
        }

        int flagMask = 0;
        String flagName = getNextArg();
        if (flagName == null) {
            getErrPrintWriter().println("Error: no permission flags specified");
            return 1;
        }
        while (flagName != null) {
            if (!SUPPORTED_PERMISSION_FLAGS.containsKey(flagName)) {
                getErrPrintWriter().println("Error: specified flag " + flagName + " is not one of "
                        + SUPPORTED_PERMISSION_FLAGS_LIST);
                return 1;
            }
            flagMask |= SUPPORTED_PERMISSION_FLAGS.get(flagName);
            flagName = getNextArg();
        }

        final UserHandle translatedUser = UserHandle.of(translateUserId(userId,
                UserHandle.USER_NULL, "runGrantRevokePermission"));
        int flagSet = setFlags ? flagMask : 0;
        mPermissionManager.updatePermissionFlags(pkg, perm, flagMask, flagSet, translatedUser);
        return 0;
    }

    private int runSetPermissionEnforced() throws RemoteException {
        final String permission = getNextArg();
        if (permission == null) {
            getErrPrintWriter().println("Error: no permission specified");
            return 1;
        }
        final String enforcedRaw = getNextArg();
        if (enforcedRaw == null) {
            getErrPrintWriter().println("Error: no enforcement specified");
            return 1;
        }
        // Permissions are always enforced now.
        return 0;
    }

    private boolean isVendorApp(String pkg) {
        try {
            final PackageInfo info = mInterface.getPackageInfo(
                     pkg, PackageManager.MATCH_ANY_USER, UserHandle.USER_SYSTEM);
            return info != null && info.applicationInfo.isVendor();
        } catch (RemoteException e) {
            return false;
        }
    }

    private boolean isProductApp(String pkg) {
        try {
            final PackageInfo info = mInterface.getPackageInfo(
                    pkg, PackageManager.MATCH_ANY_USER, UserHandle.USER_SYSTEM);
            return info != null && info.applicationInfo.isProduct();
        } catch (RemoteException e) {
            return false;
        }
    }

    private boolean isSystemExtApp(String pkg) {
        try {
            final PackageInfo info = mInterface.getPackageInfo(
                    pkg, PackageManager.MATCH_ANY_USER, UserHandle.USER_SYSTEM);
            return info != null && info.applicationInfo.isSystemExt();
        } catch (RemoteException e) {
            return false;
        }
    }

    private String getApexPackageNameContainingPackage(String pkg) {
        ApexManager apexManager = ApexManager.getInstance();
        return apexManager.getActiveApexPackageNameContainingPackage(pkg);
    }

    private boolean isApexApp(String pkg) {
        return getApexPackageNameContainingPackage(pkg) != null;
    }

    private int runGetPrivappPermissions() {
        final String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified.");
            return 1;
        }
        getOutPrintWriter().println(getPrivAppPermissionsString(pkg, true));
        return 0;
    }

    private int runGetPrivappDenyPermissions() {
        final String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified.");
            return 1;
        }
        getOutPrintWriter().println(getPrivAppPermissionsString(pkg, false));
        return 0;
    }

    @NonNull
    private String getPrivAppPermissionsString(@NonNull String packageName, boolean allowed) {
        final PermissionAllowlist permissionAllowlist =
                SystemConfig.getInstance().getPermissionAllowlist();
        final ArrayMap<String, ArrayMap<String, Boolean>> privAppPermissions;
        if (isVendorApp(packageName)) {
            privAppPermissions = permissionAllowlist.getVendorPrivilegedAppAllowlist();
        } else if (isProductApp(packageName)) {
            privAppPermissions = permissionAllowlist.getProductPrivilegedAppAllowlist();
        } else if (isSystemExtApp(packageName)) {
            privAppPermissions = permissionAllowlist.getSystemExtPrivilegedAppAllowlist();
        } else if (isApexApp(packageName)) {
            final String moduleName = ApexManager.getInstance().getApexModuleNameForPackageName(
                    getApexPackageNameContainingPackage(packageName));
            privAppPermissions = permissionAllowlist.getApexPrivilegedAppAllowlists()
                    .get(moduleName);
        } else {
            privAppPermissions = permissionAllowlist.getPrivilegedAppAllowlist();
        }
        final ArrayMap<String, Boolean> permissions = privAppPermissions != null
                ? privAppPermissions.get(packageName) : null;
        if (permissions == null) {
            return "{}";
        }
        final StringBuilder result = new StringBuilder("{");
        boolean isFirstPermission = true;
        final int permissionsSize = permissions.size();
        for (int i = 0; i < permissionsSize; i++) {
            boolean permissionAllowed = permissions.valueAt(i);
            if (permissionAllowed != allowed) {
                continue;
            }
            if (isFirstPermission) {
                isFirstPermission = false;
            } else {
                result.append(", ");
            }
            String permissionName = permissions.keyAt(i);
            result.append(permissionName);
        }
        result.append("}");
        return result.toString();
    }

    private int runGetOemPermissions() {
        final String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified.");
            return 1;
        }
        final Map<String, Boolean> oemPermissions = SystemConfig.getInstance()
                .getPermissionAllowlist().getOemAppAllowlist().get(pkg);
        if (oemPermissions == null || oemPermissions.isEmpty()) {
            getOutPrintWriter().println("{}");
        } else {
            oemPermissions.forEach((permission, granted) ->
                    getOutPrintWriter().println(permission + " granted:" + granted)
            );
        }
        return 0;
    }

    private int runGetSignaturePermissionAllowlist() {
        final var partition = getNextArg();
        if (partition == null) {
            getErrPrintWriter().println("Error: no partition specified.");
            return 1;
        }
        final var permissionAllowlist =
                SystemConfig.getInstance().getPermissionAllowlist();
        final ArrayMap<String, ArrayMap<String, Boolean>> allowlist;
        switch (partition) {
            case "system":
                allowlist = permissionAllowlist.getSignatureAppAllowlist();
                break;
            case "vendor":
                allowlist = permissionAllowlist.getVendorSignatureAppAllowlist();
                break;
            case "product":
                allowlist = permissionAllowlist.getProductSignatureAppAllowlist();
                break;
            case "system-ext":
                allowlist = permissionAllowlist.getSystemExtSignatureAppAllowlist();
                break;
            case "apex":
                allowlist = permissionAllowlist.getApexSignatureAppAllowlist();
                break;
            default:
                getErrPrintWriter().println("Error: unknown partition: " + partition);
                return 1;
        }
        final var ipw = new IndentingPrintWriter(getOutPrintWriter(), "  ");
        final var allowlistSize = allowlist.size();
        for (var allowlistIndex = 0; allowlistIndex < allowlistSize; allowlistIndex++) {
            final var packageName = allowlist.keyAt(allowlistIndex);
            final var permissions = allowlist.valueAt(allowlistIndex);
            ipw.print("Package: ");
            ipw.println(packageName);
            ipw.increaseIndent();
            final var permissionsSize = permissions.size();
            for (var permissionsIndex = 0; permissionsIndex < permissionsSize; permissionsIndex++) {
                final var permissionName = permissions.keyAt(permissionsIndex);
                final var granted = permissions.valueAt(permissionsIndex);
                if (granted) {
                    ipw.print("Permission: ");
                    ipw.println(permissionName);
                }
            }
            ipw.decreaseIndent();
        }
        return 0;
    }

    private int runGetSharedUidAllowlist() {
        final var allowlist = SystemConfig.getInstance().getPackageToSharedUidAllowList();
        final var pw = getOutPrintWriter();
        final var allowlistSize = allowlist.size();
        for (var allowlistIndex = 0; allowlistIndex < allowlistSize; allowlistIndex++) {
            final var packageName = allowlist.keyAt(allowlistIndex);
            final var sharedUserName = allowlist.valueAt(allowlistIndex);
            pw.print(packageName);
            pw.print(" ");
            pw.println(sharedUserName);
        }
        return 0;
    }

    private int runTrimCaches() throws RemoteException {
        String size = getNextArg();
        if (size == null) {
            getErrPrintWriter().println("Error: no size specified");
            return 1;
        }
        long multiplier = 1;
        int len = size.length();
        char c = size.charAt(len - 1);
        if (c < '0' || c > '9') {
            if (c == 'K' || c == 'k') {
                multiplier = 1024L;
            } else if (c == 'M' || c == 'm') {
                multiplier = 1024L*1024L;
            } else if (c == 'G' || c == 'g') {
                multiplier = 1024L*1024L*1024L;
            } else {
                getErrPrintWriter().println("Invalid suffix: " + c);
                return 1;
            }
            size = size.substring(0, len-1);
        }
        long sizeVal;
        try {
            sizeVal = Long.parseLong(size) * multiplier;
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: expected number at: " + size);
            return 1;
        }
        String volumeUuid = getNextArg();
        if ("internal".equals(volumeUuid)) {
            volumeUuid = null;
        }
        ClearDataObserver obs = new ClearDataObserver();
        mInterface.freeStorageAndNotify(volumeUuid, sizeVal,
                StorageManager.FLAG_ALLOCATE_DEFY_ALL_RESERVED, obs);
        synchronized (obs) {
            while (!obs.finished) {
                try {
                    obs.wait();
                } catch (InterruptedException e) {
                }
            }
        }
        return 0;
    }

    private static boolean isNumber(String s) {
        try {
            Integer.parseInt(s);
        } catch (NumberFormatException nfe) {
            return false;
        }
        return true;
    }

    public int runCreateUser() throws RemoteException {
        String name;
        int userId = -1;
        int flags = 0;
        String userType = null;
        String opt;
        boolean preCreateOnly = false;
        while ((opt = getNextOption()) != null) {
            String newUserType = null;
            if ("--profileOf".equals(opt)) {
                userId = translateUserId(UserHandle.parseUserArg(getNextArgRequired()),
                            UserHandle.USER_ALL, "runCreateUser");
            } else if ("--managed".equals(opt)) {
                newUserType = UserManager.USER_TYPE_PROFILE_MANAGED;
            } else if ("--restricted".equals(opt)) {
                newUserType = UserManager.USER_TYPE_FULL_RESTRICTED;
            } else if ("--guest".equals(opt)) {
                newUserType = UserManager.USER_TYPE_FULL_GUEST;
            } else if ("--demo".equals(opt)) {
                newUserType = UserManager.USER_TYPE_FULL_DEMO;
            } else if ("--ephemeral".equals(opt)) {
                flags |= UserInfo.FLAG_EPHEMERAL;
            } else if ("--for-testing".equals(opt)) {
                flags |= UserInfo.FLAG_FOR_TESTING;
            } else if ("--pre-create-only".equals(opt)) {
                preCreateOnly = true;
            } else if ("--user-type".equals(opt)) {
                newUserType = getNextArgRequired();
            } else {
                getErrPrintWriter().println("Error: unknown option " + opt);
                return 1;
            }
            // Ensure only one user-type was specified.
            if (newUserType != null) {
                if (userType != null && !userType.equals(newUserType)) {
                    getErrPrintWriter().println("Error: more than one user type was specified ("
                            + userType + " and " + newUserType + ")");
                    return 1;
                }
                userType = newUserType;
            }
        }
        String arg = getNextArg();
        if (arg == null && !preCreateOnly) {
            getErrPrintWriter().println("Error: no user name specified.");
            return 1;
        }
        if (arg != null && preCreateOnly) {
            getErrPrintWriter().println("Warning: name is ignored for pre-created users");
        }

        name = arg;
        UserInfo info = null;
        IUserManager um = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
        IAccountManager accm = IAccountManager.Stub.asInterface(
                ServiceManager.getService(Context.ACCOUNT_SERVICE));
        if (userType == null) {
            userType = UserInfo.getDefaultUserType(flags);
        }
        Trace.traceBegin(Trace.TRACE_TAG_PACKAGE_MANAGER, "shell_runCreateUser");
        try {
            if (UserManager.isUserTypeRestricted(userType)) {
                // In non-split user mode, userId can only be SYSTEM
                int parentUserId = userId >= 0 ? userId : UserHandle.USER_SYSTEM;
                info = um.createRestrictedProfileWithThrow(name, parentUserId);
                accm.addSharedAccountsFromParentUser(parentUserId, userId,
                        (Process.myUid() == Process.ROOT_UID) ? "root" : "com.android.shell");
            } else if (userId < 0) {
                info = preCreateOnly ?
                        um.preCreateUserWithThrow(userType) :
                        um.createUserWithThrow(name, userType, flags);
            } else {
                info = um.createProfileForUserWithThrow(name, userType, flags, userId, null);
            }
        } catch (ServiceSpecificException e) {
            getErrPrintWriter().println("Error: " + e);
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_PACKAGE_MANAGER);
        }

        if (info != null) {
            getOutPrintWriter().println("Success: created user id " + info.id);
            return 0;
        } else {
            getErrPrintWriter().println("Error: couldn't create User.");
            return 1;
        }
    }

    // pm remove-user [--set-ephemeral-if-in-use][--wait] USER_ID
    public int runRemoveUser() throws RemoteException {
        int userId;
        String arg;
        boolean setEphemeralIfInUse = false;
        boolean wait = false;

        while ((arg = getNextOption()) != null) {
            switch (arg) {
                case "--set-ephemeral-if-in-use":
                    setEphemeralIfInUse = true;
                    break;
                case "--wait": // fallthrough
                case "-w":
                    wait = true;
                    break;
                default:
                    getErrPrintWriter().println("Error: unknown option: " + arg);
                    return -1;
            }
        }

        arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: no user id specified.");
            return 1;
        }
        userId = UserHandle.parseUserArg(arg);
        IUserManager um = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
        if (setEphemeralIfInUse) {
            return removeUserWhenPossible(um, userId);
        } else {
            final boolean success = wait ? removeUserAndWait(um, userId) : removeUser(um, userId);
            if (success) {
                getOutPrintWriter().println("Success: removed user");
                return 0;
            } else {
                // Error message should already have been printed.
                return 1;
            }
        }
    }

    private boolean removeUser(IUserManager um, @UserIdInt int userId) throws RemoteException {
        Slog.i(TAG, "Removing user " + userId);
        if (um.removeUser(userId)) {
            return true;
        } else {
            getErrPrintWriter().println("Error: couldn't remove user id " + userId);
            return false;
        }
    }

    private boolean removeUserAndWait(IUserManager um, @UserIdInt int userId)
            throws RemoteException {
        Slog.i(TAG, "Removing (and waiting for completion) user " + userId);

        final CountDownLatch waitLatch = new CountDownLatch(1);
        final UserManagerInternal.UserLifecycleListener listener =
                new UserManagerInternal.UserLifecycleListener() {
                    @Override
                    public void onUserRemoved(UserInfo user) {
                        if (userId == user.id) {
                            waitLatch.countDown();
                        }
                    }
                };

        final UserManagerInternal umi = LocalServices.getService(UserManagerInternal.class);
        umi.addUserLifecycleListener(listener);

        try {
            if (um.removeUser(userId)) {
                final boolean awaitSuccess = waitLatch.await(10, TimeUnit.MINUTES);
                if (!awaitSuccess) {
                    getErrPrintWriter().printf("Error: Remove user %d timed out\n", userId);
                    return false;
                }
                // Success!
                return true;
            } else {
                getErrPrintWriter().println("Error: couldn't remove user id " + userId);
                return false;
            }
        } catch (InterruptedException e) {
            getErrPrintWriter().printf("Error: Remove user %d wait interrupted: %s\n", userId, e);
            Thread.currentThread().interrupt();
            return false;
        } finally {
            umi.removeUserLifecycleListener(listener);
        }
    }

    private int removeUserWhenPossible(IUserManager um, @UserIdInt int userId)
            throws RemoteException {
        Slog.i(TAG, "Removing " + userId + " or set as ephemeral if in use.");
        int result = um.removeUserWhenPossible(userId, /* overrideDevicePolicy= */ false);
        switch (result) {
            case UserManager.REMOVE_RESULT_REMOVED:
                getOutPrintWriter().printf("Success: user %d removed\n", userId);
                return 0;
            case UserManager.REMOVE_RESULT_DEFERRED:
                getOutPrintWriter().printf("Success: user %d set as ephemeral\n", userId);
                return 0;
            case UserManager.REMOVE_RESULT_ALREADY_BEING_REMOVED:
                getOutPrintWriter().printf("Success: user %d is already being removed\n", userId);
                return 0;
            case UserManager.REMOVE_RESULT_ERROR_MAIN_USER_PERMANENT_ADMIN:
                getErrPrintWriter().printf("Error: user %d is a permanent admin main user\n",
                        userId);
                return 1;
            default:
                getErrPrintWriter().printf("Error: couldn't remove or mark ephemeral user id %d\n",
                        userId);
                return 1;
        }
    }

    private int runMarkGuestForDeletion() throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: no user id specified.");
            return 1;
        }
        int userId = resolveUserId(UserHandle.parseUserArg(arg));

        IUserManager um = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
        if (!um.markGuestForDeletion(userId)) {
            getErrPrintWriter().println("Error: could not mark guest for deletion");
            return 1;
        }

        return 0;
    }

    private int runRenameUser() throws RemoteException {
        String arg = getNextArg();
        if (arg == null) {
            getErrPrintWriter().println("Error: no user id specified.");
            return 1;
        }
        int userId = resolveUserId(UserHandle.parseUserArg(arg));

        String name = getNextArg();
        if (name == null) {
            Slog.i(TAG, "Resetting name of user " + userId);
        } else {
            Slog.i(TAG, "Renaming user " + userId + " to '" + name + "'");
        }

        IUserManager um = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
        um.setUserName(userId, name);

        return 0;
    }

    public int runSetUserRestriction() throws RemoteException {
        int userId = UserHandle.USER_SYSTEM;
        String opt = getNextOption();
        if (opt != null && "--user".equals(opt)) {
            userId = UserHandle.parseUserArg(getNextArgRequired());
        }

        String restriction = getNextArg();
        String arg = getNextArg();
        boolean value;
        if ("1".equals(arg)) {
            value = true;
        } else if ("0".equals(arg)) {
            value = false;
        } else {
            getErrPrintWriter().println("Error: valid value not specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runSetUserRestriction");
        final IUserManager um = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));
        um.setUserRestriction(restriction, value, translatedUserId);
        return 0;
    }

    private int runGetUserRestriction() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        boolean getAllRestrictions = false;

        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--all":
                    getAllRestrictions = true;
                    if (getNextArg() != null) {
                        throw new IllegalArgumentException("Argument unexpected after \"--all\"");
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option " + opt);
            }
        }

        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runGetUserRestriction");
        final IUserManager um = IUserManager.Stub.asInterface(
                ServiceManager.getService(Context.USER_SERVICE));

        if (getAllRestrictions) {
            final Bundle restrictions = um.getUserRestrictions(translatedUserId);
            pw.println("All restrictions:");
            pw.println(restrictions.toString());
        } else {
            String restriction = getNextArg();
            if (restriction == null) {
                throw new IllegalArgumentException("No restriction key specified");
            }
            String unexpectedArgument = getNextArg();
            if (unexpectedArgument != null) {
                throw new IllegalArgumentException("Argument unexpected after restriction key");
            }
            pw.println(um.hasUserRestriction(restriction, translatedUserId));
        }
        return 0;
    }

    public int runSupportsMultipleUsers() {
        getOutPrintWriter().println("Is multiuser supported: "
                + UserManager.supportsMultipleUsers());
        return 0;
    }

    public int runGetMaxUsers() {
        getOutPrintWriter().println("Maximum supported users: "
                + UserManager.getMaxSupportedUsers());
        return 0;
    }

    public int runGetMaxRunningUsers() {
        ActivityManagerInternal activityManagerInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        getOutPrintWriter().println("Maximum supported running users: "
                + activityManagerInternal.getMaxRunningUsers());
        return 0;
    }

    private static class InstallParams {
        SessionParams sessionParams;
        String installerPackageName;
        int userId = UserHandle.USER_ALL;
        long stagedReadyTimeoutMs = DEFAULT_STAGED_READY_TIMEOUT_MS;
    }

    private InstallParams makeInstallParams(Set<String> unsupportedOptions) {
        final SessionParams sessionParams = new SessionParams(SessionParams.MODE_FULL_INSTALL);
        final InstallParams params = new InstallParams();

        params.sessionParams = sessionParams;
        // Allowlist all permissions by default
        sessionParams.installFlags |= PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
        // Set package source to other by default. Can be overridden by "--package-source"
        sessionParams.setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER);

        // Encodes one of the states:
        //  1. Install request explicitly specified --staged, then value will be true.
        //  2. Install request explicitly specified --non-staged, then value will be false.
        //  3. Install request did not specify either --staged or --non-staged, then for APEX
        //      installs the value will be true, and for apk installs it will be false.
        Boolean staged = null;

        String opt;
        boolean replaceExisting = true;
        boolean forceNonStaged = false;
        while ((opt = getNextOption()) != null) {
            if (unsupportedOptions.contains(opt)) {
                throw new IllegalArgumentException("Unsupported option " + opt);
            }
            switch (opt) {
                case "-r": // ignore
                    break;
                case "-R":
                    replaceExisting = false;
                    break;
                case "-i":
                    params.installerPackageName = getNextArg();
                    if (params.installerPackageName == null) {
                        throw new IllegalArgumentException("Missing installer package");
                    }
                    break;
                case "-t":
                    sessionParams.installFlags |= PackageManager.INSTALL_ALLOW_TEST;
                    break;
                case "-f":
                    sessionParams.installFlags |= PackageManager.INSTALL_INTERNAL;
                    break;
                case "-d":
                    sessionParams.installFlags |= PackageManager.INSTALL_REQUEST_DOWNGRADE;
                    break;
                case "-g":
                    sessionParams.installFlags |=
                            PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS;
                    break;
                case "--restrict-permissions":
                    sessionParams.installFlags &=
                            ~PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
                    break;
                case "--dont-kill":
                    sessionParams.installFlags |= PackageManager.INSTALL_DONT_KILL_APP;
                    break;
                case "--originating-uri":
                    sessionParams.originatingUri = Uri.parse(getNextArg());
                    break;
                case "--referrer":
                    sessionParams.referrerUri = Uri.parse(getNextArg());
                    break;
                case "-p":
                    sessionParams.mode = SessionParams.MODE_INHERIT_EXISTING;
                    sessionParams.appPackageName = getNextArg();
                    if (sessionParams.appPackageName == null) {
                        throw new IllegalArgumentException("Missing inherit package name");
                    }
                    break;
                case "--pkg":
                    sessionParams.appPackageName = getNextArg();
                    if (sessionParams.appPackageName == null) {
                        throw new IllegalArgumentException("Missing package name");
                    }
                    break;
                case "-S":
                    final long sizeBytes = Long.parseLong(getNextArg());
                    if (sizeBytes <= 0) {
                        throw new IllegalArgumentException("Size must be positive");
                    }
                    sessionParams.setSize(sizeBytes);
                    break;
                case "--abi":
                    sessionParams.abiOverride = checkAbiArgument(getNextArg());
                    break;
                case "--ephemeral":
                case "--instant":
                case "--instantapp":
                    sessionParams.setInstallAsInstantApp(true /*isInstantApp*/);
                    break;
                case "--full":
                    sessionParams.setInstallAsInstantApp(false /*isInstantApp*/);
                    break;
                case "--preload":
                    sessionParams.setInstallAsVirtualPreload();
                    break;
                case "--user":
                    params.userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                case "--install-location":
                    sessionParams.installLocation = Integer.parseInt(getNextArg());
                    break;
                case "--install-reason":
                    sessionParams.installReason = Integer.parseInt(getNextArg());
                    break;
                case "--update-ownership":
                    if (params.installerPackageName == null) {
                        // Enabling update ownership enforcement needs an installer. Since the
                        // default installer is null when using adb install, that effectively
                        // disable this enforcement.
                        params.installerPackageName = "com.android.shell";
                    }
                    sessionParams.installFlags |= PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP;
                    break;
                case "--force-uuid":
                    sessionParams.installFlags |= PackageManager.INSTALL_FORCE_VOLUME_UUID;
                    sessionParams.volumeUuid = getNextArg();
                    if ("internal".equals(sessionParams.volumeUuid)) {
                        sessionParams.volumeUuid = null;
                    }
                    break;
                case "--force-sdk": // ignore
                    break;
                case "--apex":
                    sessionParams.setInstallAsApex();
                    break;
                case "--force-non-staged":
                    forceNonStaged = true;
                    break;
                case "--multi-package":
                    sessionParams.setMultiPackage();
                    break;
                case "--staged":
                    staged = true;
                    break;
                case "--non-staged":
                    staged = false;
                    break;
                case "--force-queryable":
                    sessionParams.setForceQueryable();
                    break;
                case "--enable-rollback":
                    if (params.installerPackageName == null) {
                        // com.android.shell has the TEST_MANAGE_ROLLBACKS
                        // permission needed to enable rollback for non-module
                        // packages, which is likely what the user wants when
                        // enabling rollback through the shell command. Set
                        // the installer to com.android.shell if no installer
                        // has been provided so that the user doesn't have to
                        // remember to set it themselves.
                        params.installerPackageName = "com.android.shell";
                    }
                    int rollbackStrategy = PackageManager.ROLLBACK_DATA_POLICY_RESTORE;
                    try {
                        rollbackStrategy = Integer.parseInt(peekNextArg());
                        if (rollbackStrategy < PackageManager.ROLLBACK_DATA_POLICY_RESTORE
                                || rollbackStrategy > PackageManager.ROLLBACK_DATA_POLICY_RETAIN) {
                            throw new IllegalArgumentException(
                                    rollbackStrategy + " is not a valid rollback data policy.");
                        }
                        getNextArg(); // pop the argument
                    } catch (NumberFormatException e) {
                        // not followed by a number assume ROLLBACK_DATA_POLICY_RESTORE.
                    }
                    sessionParams.setEnableRollback(true, rollbackStrategy);
                    break;
                case "--rollback-impact-level":
                    if (!Flags.recoverabilityDetection()) {
                        throw new IllegalArgumentException("Unknown option " + opt);
                    }
                    int rollbackImpactLevel = Integer.parseInt(peekNextArg());
                    if (rollbackImpactLevel < PackageManager.ROLLBACK_USER_IMPACT_LOW
                            || rollbackImpactLevel
                                    > PackageManager.ROLLBACK_USER_IMPACT_ONLY_MANUAL) {
                        throw new IllegalArgumentException(
                            rollbackImpactLevel + " is not a valid rollback impact level.");
                    }
                    sessionParams.setRollbackImpactLevel(rollbackImpactLevel);
                case "--staged-ready-timeout":
                    params.stagedReadyTimeoutMs = Long.parseLong(getNextArgRequired());
                    break;
                case "--skip-verification":
                    sessionParams.installFlags |= PackageManager.INSTALL_DISABLE_VERIFICATION;
                    break;
                case "--skip-enable":
                    sessionParams.setApplicationEnabledSettingPersistent();
                    break;
                case "--bypass-low-target-sdk-block":
                    sessionParams.installFlags |=
                            PackageManager.INSTALL_BYPASS_LOW_TARGET_SDK_BLOCK;
                    break;
                case "--ignore-dexopt-profile":
                    sessionParams.installFlags |= PackageManager.INSTALL_IGNORE_DEXOPT_PROFILE;
                    break;
                case "--package-source":
                    sessionParams.setPackageSource(Integer.parseInt(getNextArg()));
                    break;
                case "--dexopt-compiler-filter":
                    sessionParams.dexoptCompilerFilter = getNextArgRequired();
                    // An early check that throws IllegalArgumentException if the compiler filter is
                    // invalid.
                    new DexoptParams.Builder(ReasonMapping.REASON_INSTALL)
                            .setCompilerFilter(sessionParams.dexoptCompilerFilter)
                            .build();
                    break;
                case "--force-verification":
                    sessionParams.setForceVerification();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option " + opt);
            }
        }
        if (staged == null) {
            staged = (sessionParams.installFlags & PackageManager.INSTALL_APEX) != 0;
        }
        if (replaceExisting) {
            sessionParams.installFlags |= PackageManager.INSTALL_REPLACE_EXISTING;
        }
        if (forceNonStaged) {
            sessionParams.isStaged = false;
            sessionParams.developmentInstallFlags |=
                    PackageManager.INSTALL_DEVELOPMENT_FORCE_NON_STAGED_APEX_UPDATE;
        } else if (staged) {
            sessionParams.setStaged();
        }
        if ((sessionParams.installFlags & PackageManager.INSTALL_APEX) != 0
                && (sessionParams.installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0
                && sessionParams.rollbackDataPolicy == PackageManager.ROLLBACK_DATA_POLICY_WIPE) {
            throw new IllegalArgumentException("Data policy 'wipe' is not supported for apex.");
        }
        return params;
    }

    private int runSetHomeActivity() {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_SYSTEM;
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--user":
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return 1;
            }
        }

        String pkgName;
        String component = getNextArg();
        if (component.indexOf('/') < 0) {
            // No component specified, so assume it's just a package name.
            pkgName = component;
        } else {
            ComponentName componentName =
                    component != null ? ComponentName.unflattenFromString(component) : null;
            if (componentName == null) {
                pw.println("Error: invalid component name");
                return 1;
            }
            pkgName = componentName.getPackageName();
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runSetHomeActivity");
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        try {
            RoleManager roleManager = mContext.getSystemService(RoleManager.class);
            roleManager.addRoleHolderAsUser(RoleManager.ROLE_HOME, pkgName, 0,
                    UserHandle.of(translatedUserId), FgThread.getExecutor(), future::complete);
            boolean success = future.get();
            if (success) {
                pw.println("Success");
                return 0;
            } else {
                pw.println("Error: Failed to set default home.");
                return 1;
            }
        } catch (Exception e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runSetInstaller() throws RemoteException {
        final String targetPackage = getNextArg();
        final String installerPackageName = getNextArg();

        if (targetPackage == null || installerPackageName == null) {
            getErrPrintWriter().println("Must provide both target and installer package names");
            return 1;
        }

        mInterface.setInstallerPackageName(targetPackage, installerPackageName);
        getOutPrintWriter().println("Success");
        return 0;
    }

    private int runGetInstantAppResolver() {
        final PrintWriter pw = getOutPrintWriter();
        try {
            final ComponentName instantAppsResolver = mInterface.getInstantAppResolverComponent();
            if (instantAppsResolver == null) {
                return 1;
            }
            pw.println(instantAppsResolver.flattenToString());
            return 0;
        } catch (Exception e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runHasFeature() {
        final PrintWriter err = getErrPrintWriter();
        final String featureName = getNextArg();
        if (featureName == null) {
            err.println("Error: expected FEATURE name");
            return 1;
        }
        final String versionString = getNextArg();
        try {
            final int version = (versionString == null) ? 0 : Integer.parseInt(versionString);
            final boolean hasFeature = mInterface.hasSystemFeature(featureName, version);
            getOutPrintWriter().println(hasFeature);
            return hasFeature ? 0 : 1;
        } catch (NumberFormatException e) {
            err.println("Error: illegal version number " + versionString);
            return 1;
        } catch (RemoteException e) {
            err.println(e.toString());
            return 1;
        }
    }

    private int runDump() {
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        ActivityManager.dumpPackageStateStatic(getOutFileDescriptor(), pkg);
        return 0;
    }

    private int runDumpPackage() {
        String pkg = getNextArg();
        if (pkg == null) {
            getErrPrintWriter().println("Error: no package specified");
            return 1;
        }
        try {
            ((IBinder) mInterface).dump(getOutFileDescriptor(), new String[]{pkg});
        } catch (Throwable e) {
            PrintWriter pw = getErrPrintWriter();
            pw.println("Failure dumping service:");
            e.printStackTrace(pw);
            pw.flush();
        }
        return 0;
    }

    private int runSetHarmfulAppWarning() throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runSetHarmfulAppWarning");
        final String packageName = getNextArgRequired();
        final String warning = getNextArg();

        mInterface.setHarmfulAppWarning(packageName, warning, translatedUserId);

        return 0;
    }

    private int runGetHarmfulAppWarning() throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_NULL, "runGetHarmfulAppWarning");
        final String packageName = getNextArgRequired();
        final CharSequence warning = mInterface.getHarmfulAppWarning(packageName, translatedUserId);
        if (!TextUtils.isEmpty(warning)) {
            getOutPrintWriter().println(warning);
            return 0;
        } else {
            return 1;
        }
    }

    private int runSetSilentUpdatesPolicy() {
        final PrintWriter pw = getOutPrintWriter();
        String opt;
        String installerPackageName = null;
        Long throttleTimeInSeconds = null;
        boolean reset = false;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--allow-unlimited-silent-updates":
                    installerPackageName = getNextArgRequired();
                    break;
                case "--throttle-time":
                    throttleTimeInSeconds = Long.parseLong(getNextArgRequired());
                    break;
                case "--reset":
                    reset = true;
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return -1;
            }
        }
        if (throttleTimeInSeconds != null && throttleTimeInSeconds < 0) {
            pw.println("Error: Invalid value for \"--throttle-time\":" + throttleTimeInSeconds);
            return -1;
        }

        try {
            final IPackageInstaller installer = mInterface.getPackageInstaller();
            if (reset) {
                installer.setAllowUnlimitedSilentUpdates(null /* installerPackageName */);
                installer.setSilentUpdatesThrottleTime(-1 /* restore to the default */);
            } else {
                if (installerPackageName != null) {
                    installer.setAllowUnlimitedSilentUpdates(installerPackageName);
                }
                if (throttleTimeInSeconds != null) {
                    installer.setSilentUpdatesThrottleTime(throttleTimeInSeconds);
                }
            }
        } catch (RemoteException e) {
            pw.println("Failure ["
                    + e.getClass().getName() + " - "
                    + e.getMessage() + "]");
            return -1;
        }
        return 1;
    }

    private int runGetAppMetadata() {
        mContext.enforceCallingOrSelfPermission(GET_APP_METADATA, "getAppMetadataFd");
        final PrintWriter pw = getOutPrintWriter();
        String pkgName = getNextArgRequired();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = mInterface.getAppMetadataFd(pkgName, mContext.getUserId());
        } catch (RemoteException e) {
            pw.println("Failure [" + e.getClass().getName() + " - " + e.getMessage() + "]");
            return -1;
        }
        if (pfd != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(pfd)))) {
                while (br.ready()) {
                    pw.println(br.readLine());
                }
            } catch (IOException e) {
                pw.println("Failure [" + e.getClass().getName() + " - " + e.getMessage() + "]");
                return -1;
            }
        }
        return 1;
    }

    private int runWaitForHandler(boolean forBackgroundHandler) {
        final PrintWriter pw = getOutPrintWriter();
        long timeoutMillis = 60000; // default timeout is 60 seconds
        String opt;
        while ((opt = getNextOption()) != null) {
            switch (opt) {
                case "--timeout":
                    timeoutMillis = Long.parseLong(getNextArgRequired());
                    break;
                default:
                    pw.println("Error: Unknown option: " + opt);
                    return -1;
            }
        }
        if (timeoutMillis <= 0) {
            pw.println("Error: --timeout value must be positive: " + timeoutMillis);
            return -1;
        }
        final boolean success;
        try {
            success = mInterface.waitForHandler(timeoutMillis, forBackgroundHandler);
        } catch (RemoteException e) {
            pw.println("Failure [" + e.getClass().getName() + " - " + e.getMessage() + "]");
            return -1;
        }
        if (success) {
            pw.println("Success");
            return 0;
        } else {
            pw.println("Timeout. PackageManager handlers are still busy.");
            return -1;
        }
    }

    private int runArtServiceCommand() {
        try (var in = ParcelFileDescriptor.dup(getInFileDescriptor());
                var out = ParcelFileDescriptor.dup(getOutFileDescriptor());
                var err = ParcelFileDescriptor.dup(getErrFileDescriptor())) {
            return LocalManagerRegistry.getManagerOrThrow(ArtManagerLocal.class)
                    .handleShellCommand(getTarget(), in, out, err, getAllArgs());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (ManagerNotFoundException e) {
            PrintWriter epw = getErrPrintWriter();
            epw.println("ART Service is not ready. Please try again later");
            return -1;
        }
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

    private int translateUserId(int userId, int allUserId, String logContext) {
        final boolean allowAll = (allUserId != UserHandle.USER_NULL);
        final int translatedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, allowAll, true, logContext, "pm command");
        return translatedUserId == UserHandle.USER_ALL ? allUserId : translatedUserId;
    }

    private int doCreateSession(SessionParams params, String installerPackageName, int userId)
            throws RemoteException {
        if (userId == UserHandle.USER_ALL) {
            params.installFlags |= PackageManager.INSTALL_ALL_USERS;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "doCreateSession");
        final int sessionId = mInterface.getPackageInstaller()
                .createSession(params, installerPackageName, null /*installerAttributionTag*/,
                        translatedUserId);
        return sessionId;
    }

    private int doAddFiles(int sessionId, ArrayList<String> args, long sessionSizeBytes,
            boolean isApex, boolean installArchived) throws RemoteException {
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));

            // 1. Single file from stdin.
            if (args.isEmpty() || STDIN_PATH.equals(args.get(0))) {
                final String name = "base" + RANDOM.nextInt() + "." + (isApex ? "apex" : "apk");
                final long size;
                final Metadata metadata;
                if (!installArchived) {
                    metadata = Metadata.forStdIn(name);
                    size = sessionSizeBytes;
                } else {
                    metadata = Metadata.forArchived(
                            getArchivedPackage(STDIN_PATH, sessionSizeBytes));
                    size = -1;
                }
                session.addFile(LOCATION_DATA_APP, name, size, metadata.toByteArray(), null);
                return 0;
            }

            for (String arg : args) {
                final int delimLocation = arg.indexOf(':');

                if (delimLocation != -1) {
                    // 2. File with specified size read from stdin.
                    if (installArchived) {
                        getOutPrintWriter().println(
                                "Error: can't install with size from STDIN for Archival install");
                        return 1;
                    }
                    if (processArgForStdin(arg, session) != 0) {
                        return 1;
                    }
                } else {
                    // 3. Local file.
                    processArgForLocalFile(arg, session, installArchived);
                }
            }
            return 0;
        } catch (IOException | IllegalArgumentException e) {
            getErrPrintWriter().println("Failed to add file(s), reason: " + e);
            getOutPrintWriter().println("Failure [failed to add file(s)]");
            return 1;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int processArgForStdin(String arg, PackageInstaller.Session session) {
        final String[] fileDesc = arg.split(":");
        String name, fileId;
        long sizeBytes;
        byte[] signature = null;
        int streamingVersion = 0;

        try {
            if (fileDesc.length < 2) {
                getErrPrintWriter().println("Must specify file name and size");
                return 1;
            }
            name = fileDesc[0];
            sizeBytes = Long.parseUnsignedLong(fileDesc[1]);
            fileId = name;

            if (fileDesc.length > 2 && !TextUtils.isEmpty(fileDesc[2])) {
                fileId = fileDesc[2];
            }
            if (fileDesc.length > 3) {
                signature = Base64.getDecoder().decode(fileDesc[3]);
            }
            if (fileDesc.length > 4) {
                streamingVersion = Integer.parseUnsignedInt(fileDesc[4]);
                if (streamingVersion < 0 || streamingVersion > 1) {
                    getErrPrintWriter().println(
                            "Unsupported streaming version: " + streamingVersion);
                    return 1;
                }
            }
        } catch (IllegalArgumentException e) {
            getErrPrintWriter().println(
                    "Unable to parse file parameters: " + arg + ", reason: " + e);
            return 1;
        }

        if (TextUtils.isEmpty(name)) {
            getErrPrintWriter().println("Empty file name in: " + arg);
            return 1;
        }

        final Metadata metadata;

        if (signature != null) {
            // Streaming/adb mode. Versions:
            // 0: data only streaming, tree has to be fully available,
            // 1: tree and data streaming.
            metadata = (streamingVersion == 0) ? Metadata.forDataOnlyStreaming(fileId)
                    : Metadata.forStreaming(fileId);
            try {
                if ((signature.length > 0) && (V4Signature.readFrom(signature) == null)) {
                    getErrPrintWriter().println("V4 signature is invalid in: " + arg);
                    return 1;
                }
            } catch (Exception e) {
                getErrPrintWriter().println(
                        "V4 signature is invalid: " + e + " in " + arg);
                return 1;
            }
        } else {
            // Single-shot read from stdin.
            metadata = Metadata.forStdIn(fileId);
        }

        session.addFile(LOCATION_DATA_APP, name, sizeBytes, metadata.toByteArray(), signature);
        return 0;
    }

    private long getFileStatSize(File file) {
        final ParcelFileDescriptor pfd = openFileForSystem(file.getPath(), "r");
        if (pfd == null) {
            throw new IllegalArgumentException("Error: Can't open file: " + file.getPath());
        }
        try {
            return pfd.getStatSize();
        } finally {
            IoUtils.closeQuietly(pfd);
        }
    }

    private ArchivedPackageParcel getArchivedPackage(String inPath, long sizeBytes)
            throws RemoteException, IOException {
        final var fdWithSize = openInFile(inPath, sizeBytes);
        if (fdWithSize.first == null) {
            throw new IllegalArgumentException("Error: Can't open file: " + inPath);
        }

        final String encoded;
        final ParcelFileDescriptor fd = fdWithSize.first;
        final int size = (int) (long) fdWithSize.second;
        try (InputStream inStream = new AutoCloseInputStream(fd)) {
            byte[] bytes = new byte[size];
            Streams.readFully(inStream, bytes);
            encoded = new String(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("Error: Can't load archived package from: " + inPath,
                    e);
        }

        var result = Metadata.readArchivedPackageParcel(HexEncoding.decode(encoded));
        if (result == null) {
            throw new IllegalArgumentException(
                    "Error: Can't parse archived package from: " + inPath);
        }
        return result;
    }

    private void processArgForLocalFile(String arg, PackageInstaller.Session session,
            boolean installArchived) throws IOException, RemoteException {
        final String inPath = arg;

        final File file = new File(inPath);
        final String name = file.getName();
        final long size;
        final Metadata metadata;
        if (installArchived) {
            metadata = Metadata.forArchived(getArchivedPackage(inPath, -1));
            size = 0;
        } else {
            metadata = Metadata.forLocalFile(inPath);
            size = getFileStatSize(file);
        }

        byte[] v4signatureBytes = null;
        if (!installArchived) {
            // Try to load the v4 signature file for the APK; it might not exist.
            final String v4SignaturePath = inPath + V4Signature.EXT;
            final ParcelFileDescriptor pfd = openFileForSystem(v4SignaturePath, "r");
            if (pfd != null) {
                try {
                    final V4Signature v4signature = V4Signature.readFrom(pfd);
                    v4signatureBytes = v4signature.toByteArray();
                } catch (IOException ex) {
                    Slog.e(TAG, "V4 signature file exists but failed to be parsed.", ex);
                } finally {
                    IoUtils.closeQuietly(pfd);
                }
            }
        }

        session.addFile(LOCATION_DATA_APP, name, size, metadata.toByteArray(), v4signatureBytes);
    }

    private int doWriteSplits(int sessionId, ArrayList<String> splitPaths, long sessionSizeBytes,
            boolean isApex) throws RemoteException {
        final boolean multipleSplits = splitPaths.size() > 1;
        for (String splitPath : splitPaths) {
            String splitName = multipleSplits ? new File(splitPath).getName()
                    : "base." + (isApex ? "apex" : "apk");

            if (doWriteSplit(sessionId, splitPath, sessionSizeBytes, splitName,
                    false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
                return 1;
            }
        }
        return 0;
    }

    private Pair<ParcelFileDescriptor, Long> openInFile(String inPath, long sizeBytes)
            throws IOException {
        final ParcelFileDescriptor fd;
        if (STDIN_PATH.equals(inPath)) {
            fd = ParcelFileDescriptor.dup(getInFileDescriptor());
        } else if (inPath != null) {
            fd = openFileForSystem(inPath, "r");
            if (fd == null) {
                return Pair.create(null, -1L);
            }
            sizeBytes = fd.getStatSize();
            if (sizeBytes < 0) {
                fd.close();
                getErrPrintWriter().println("Unable to get size of: " + inPath);
                return Pair.create(null, -1L);
            }
        } else {
            fd = ParcelFileDescriptor.dup(getInFileDescriptor());
        }
        if (sizeBytes <= 0) {
            getErrPrintWriter().println("Error: must specify an APK size");
            return Pair.create(null, 1L);
        }
        return Pair.create(fd, sizeBytes);
    }

    private int doWriteSplit(int sessionId, String inPath, long sizeBytes, String splitName,
            boolean logSuccess) throws RemoteException {
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));

            final PrintWriter pw = getOutPrintWriter();

            final var fdWithSize = openInFile(inPath, sizeBytes);
            if (fdWithSize.first == null) {
                long resultCode = fdWithSize.second;
                return (int) resultCode;
            }
            final ParcelFileDescriptor fd = fdWithSize.first;
            sizeBytes = fdWithSize.second;

            session.write(splitName, 0, sizeBytes, fd);

            if (logSuccess) {
                pw.println("Success: streamed " + sizeBytes + " bytes");
            }
            return 0;
        } catch (IOException e) {
            getErrPrintWriter().println("Error: failed to write; " + e.getMessage());
            return 1;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doInstallAddSession(int parentId, int[] sessionIds, boolean logSuccess)
            throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(parentId));
            if (!session.isMultiPackage()) {
                getErrPrintWriter().println(
                        "Error: parent session ID is not a multi-package session");
                return 1;
            }
            for (int i = 0; i < sessionIds.length; i++) {
                session.addChildSessionId(sessionIds[i]);
            }
            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doRemoveSplits(int sessionId, Collection<String> splitNames, boolean logSuccess)
            throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            for (String splitName : splitNames) {
                session.removeSplit(splitName);
            }

            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } catch (IOException e) {
            pw.println("Error: failed to remove split; " + e.getMessage());
            return 1;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doCommitSession(int sessionId, boolean logSuccess)
            throws RemoteException {

        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            if (!session.isMultiPackage() && !session.isStaged()) {
                // Validity check that all .dm files match an apk.
                // (The installer does not support standalone .dm files and will not process them.)
                try {
                    DexMetadataHelper.validateDexPaths(session.getNames());
                } catch (IllegalStateException | IOException e) {
                    pw.println(
                            "Warning [Could not validate the dex paths: " + e.getMessage() + "]");
                }
            }
            final LocalIntentReceiver receiver = new LocalIntentReceiver();
            session.commit(receiver.getIntentSender());
            if (!session.isStaged()) {
                final Intent result = receiver.getResult();
                int status = result.getIntExtra(
                        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                List<String> warnings =
                        result.getStringArrayListExtra(PackageInstaller.EXTRA_WARNINGS);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    if (!ArrayUtils.isEmpty(warnings)) {
                        // Don't start the output string with "Success" because that will make adb
                        // treat this as a success.
                        for (String warning : warnings) {
                            pw.println("Warning: " + warning);
                        }
                        // Treat warnings as failure to draw app developers' attention.
                        status = PackageInstaller.STATUS_FAILURE;
                        pw.println("Completed with warning(s)");
                    } else if (logSuccess) {
                        pw.println("Success");
                    }
                } else {
                    pw.println("Failure ["
                            + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
                }
                return status;
            } else {
                // Return immediately without retrieving the result. The caller will decide
                // whether to wait for the session to become ready.
                if (logSuccess) {
                    pw.println("Success");
                }
                return PackageInstaller.STATUS_SUCCESS;
            }
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private int doAbandonSession(int sessionId, boolean logSuccess) throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        PackageInstaller.Session session = null;
        try {
            session = new PackageInstaller.Session(
                    mInterface.getPackageInstaller().openSession(sessionId));
            session.abandon();
            if (logSuccess) {
                pw.println("Success");
            }
            return 0;
        } finally {
            IoUtils.closeQuietly(session);
        }
    }

    private void doListPermissions(ArrayList<String> groupList, boolean groups, boolean labels,
            boolean summary, int startProtectionLevel, int endProtectionLevel)
                    throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int groupCount = groupList.size();
        for (int i = 0; i < groupCount; i++) {
            String groupName = groupList.get(i);
            String prefix = "";
            if (groups) {
                if (i > 0) {
                    pw.println("");
                }
                if (groupName != null) {
                    PermissionGroupInfo pgi =
                            mInterface.getPermissionGroupInfo(groupName, 0 /*flags*/);
                    if (summary) {
                        Resources res = getResources(pgi);
                        if (res != null) {
                            pw.print(loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel) + ": ");
                        } else {
                            pw.print(pgi.name + ": ");

                        }
                    } else {
                        pw.println((labels ? "+ " : "") + "group:" + pgi.name);
                        if (labels) {
                            pw.println("  package:" + pgi.packageName);
                            Resources res = getResources(pgi);
                            if (res != null) {
                                pw.println("  label:"
                                        + loadText(pgi, pgi.labelRes, pgi.nonLocalizedLabel));
                                pw.println("  description:"
                                        + loadText(pgi, pgi.descriptionRes,
                                                pgi.nonLocalizedDescription));
                            }
                        }
                    }
                } else {
                    pw.println(((labels && !summary) ? "+ " : "") + "ungrouped:");
                }
                prefix = "  ";
            }
            List<PermissionInfo> ps = mPermissionManager
                    .queryPermissionsByGroup(groupList.get(i), 0 /*flags*/);
            final int count = (ps == null ? 0 : ps.size());
            boolean first = true;
            for (int p = 0 ; p < count ; p++) {
                PermissionInfo pi = ps.get(p);
                if (groups && groupName == null && pi.group != null) {
                    continue;
                }
                final int base = pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
                if (base < startProtectionLevel
                        || base > endProtectionLevel) {
                    continue;
                }
                if (summary) {
                    if (first) {
                        first = false;
                    } else {
                        pw.print(", ");
                    }
                    Resources res = getResources(pi);
                    if (res != null) {
                        pw.print(loadText(pi, pi.labelRes,
                                pi.nonLocalizedLabel));
                    } else {
                        pw.print(pi.name);
                    }
                } else {
                    pw.println(prefix + (labels ? "+ " : "")
                            + "permission:" + pi.name);
                    if (labels) {
                        pw.println(prefix + "  package:" + pi.packageName);
                        Resources res = getResources(pi);
                        if (res != null) {
                            pw.println(prefix + "  label:"
                                    + loadText(pi, pi.labelRes,
                                            pi.nonLocalizedLabel));
                            pw.println(prefix + "  description:"
                                    + loadText(pi, pi.descriptionRes,
                                            pi.nonLocalizedDescription));
                        }
                        pw.println(prefix + "  protectionLevel:"
                                + PermissionInfo.protectionToString(pi.protectionLevel));
                    }
                }
            }

            if (summary) {
                pw.println("");
            }
        }
    }

    private String loadText(PackageItemInfo pii, int res, CharSequence nonLocalized)
            throws RemoteException {
        if (nonLocalized != null) {
            return nonLocalized.toString();
        }
        if (res != 0) {
            Resources r = getResources(pii);
            if (r != null) {
                try {
                    return r.getString(res);
                } catch (Resources.NotFoundException e) {
                }
            }
        }
        return null;
    }

    private Resources getResources(PackageItemInfo pii) throws RemoteException {
        Resources res = mResourceCache.get(pii.packageName);
        if (res != null) return res;

        ApplicationInfo ai = mInterface.getApplicationInfo(pii.packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS
                        | PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS
                        | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS, 0);
        if (ai == null) {
            Slog.e(TAG, "Failed to get ApplicationInfo for package name(" + pii.packageName + ").");
            return null;
        }
        AssetManager am = new AssetManager();
        am.addAssetPath(ai.publicSourceDir);
        res = new Resources(am, null, null);
        mResourceCache.put(pii.packageName, res);
        return res;
    }

    // Resolves the userId; supports UserHandle.USER_CURRENT, but not other special values
    private @UserIdInt int resolveUserId(@UserIdInt int userId) {
        return userId == UserHandle.USER_CURRENT ? ActivityManager.getCurrentUser() : userId;
    }

    private int runClearPackagePreferredActivities() {
        final PrintWriter pw = getErrPrintWriter();
        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }
        try {
            mContext.getPackageManager().clearPackagePreferredActivities(packageName);
            return 0;
        } catch (Exception e) {
            pw.println(e.toString());
            return 1;
        }
    }

    private int runArchive() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int flags = 0;
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) {
                    UserManagerInternal umi =
                            LocalServices.getService(UserManagerInternal.class);
                    UserInfo userInfo = umi.getUserInfo(userId);
                    if (userInfo == null) {
                        pw.println("Failure [user " + userId + " doesn't exist]");
                        return 1;
                    }
                }
            } else {
                pw.println("Error: Unknown option: " + opt);
                return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        if (userId == UserHandle.USER_ALL) {
            flags |= PackageManager.DELETE_ALL_USERS;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "runArchive");
        final LocalIntentReceiver receiver = new LocalIntentReceiver();

        try {
            mInterface.getPackageInstaller().requestArchive(packageName,
                    /* callerPackageName= */ "", flags, receiver.getIntentSender(),
                    new UserHandle(translatedUserId));
        } catch (Exception e) {
            pw.println("Failure [" + e.getMessage() + "]");
            return 1;
        }

        final Intent result = receiver.getResult();
        final int status = result.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            pw.println("Success");
            return 0;
        } else {
            pw.println("Failure ["
                    + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + "]");
            return 1;
        }
    }

    private int runUnarchive() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) {
                    UserManagerInternal umi =
                            LocalServices.getService(UserManagerInternal.class);
                    UserInfo userInfo = umi.getUserInfo(userId);
                    if (userInfo == null) {
                        pw.println("Failure [user " + userId + " doesn't exist]");
                        return 1;
                    }
                }
            } else {
                pw.println("Error: Unknown option: " + opt);
                return 1;
            }
        }

        final String packageName = getNextArg();
        if (packageName == null) {
            pw.println("Error: package name not specified");
            return 1;
        }

        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "runArchive");
        final LocalIntentReceiver receiver = new LocalIntentReceiver();

        try {
            mInterface.getPackageInstaller().requestUnarchive(packageName,
                    mContext.getPackageName(), receiver.getIntentSender(),
                    new UserHandle(translatedUserId));
        } catch (Exception e) {
            pw.println("Failure [" + e.getMessage() + "]");
            return 1;
        }

        pw.println("Success");
        return 0;
    }

    private int runGetDomainVerificationAgent() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) {
                    UserManagerInternal umi =
                            LocalServices.getService(UserManagerInternal.class);
                    UserInfo userInfo = umi.getUserInfo(userId);
                    if (userInfo == null) {
                        pw.println("Failure [user " + userId + " doesn't exist]");
                        return 1;
                    }
                }
            } else {
                pw.println("Error: Unknown option: " + opt);
                return 1;
            }
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "runGetDomainVerificationAgent");
        try {
            final ComponentName domainVerificationAgent =
                    mInterface.getDomainVerificationAgent(translatedUserId);
            pw.println(domainVerificationAgent == null
                    ? "No Domain Verifier available!" : domainVerificationAgent.flattenToString());
        } catch (Exception e) {
            pw.println("Failure [" + e.getMessage() + "]");
            return 1;
        }
        return 0;
    }

    private int runGetVerificationPolicy() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) {
                    UserManagerInternal umi =
                            LocalServices.getService(UserManagerInternal.class);
                    UserInfo userInfo = umi.getUserInfo(userId);
                    if (userInfo == null) {
                        pw.println("Failure [user " + userId + " doesn't exist]");
                        return 1;
                    }
                }
            } else {
                pw.println("Error: Unknown option: " + opt);
                return 1;
            }
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "runGetVerificationPolicy");
        try {
            final IPackageInstaller installer = mInterface.getPackageInstaller();
            // TODO(b/360129657): global verification policy should be per user
            final int policy = installer.getVerificationPolicy(translatedUserId);
            pw.println(policy);
        } catch (Exception e) {
            pw.println("Failure [" + e.getMessage() + "]");
            return 1;
        }
        return 0;
    }

    private int runSetVerificationPolicy() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId != UserHandle.USER_ALL && userId != UserHandle.USER_CURRENT) {
                    UserManagerInternal umi =
                            LocalServices.getService(UserManagerInternal.class);
                    UserInfo userInfo = umi.getUserInfo(userId);
                    if (userInfo == null) {
                        pw.println("Failure [user " + userId + " doesn't exist]");
                        return 1;
                    }
                }
            } else {
                pw.println("Error: Unknown option: " + opt);
                return 1;
            }
        }
        final String policyStr = getNextArg();
        if (policyStr == null) {
            pw.println("Error: policy not specified");
            return 1;
        }
        final int translatedUserId =
                translateUserId(userId, UserHandle.USER_SYSTEM, "runSetVerificationPolicy");
        try {
            final IPackageInstaller installer = mInterface.getPackageInstaller();
            // TODO(b/360129657): global verification policy should be per user
            final boolean success = installer.setVerificationPolicy(Integer.parseInt(policyStr),
                    translatedUserId);
            if (!success) {
                pw.println("Failure setting verification policy.");
                return 1;
            }
        } catch (Exception e) {
            pw.println("Failure [" + e.getMessage() + "]");
            return 1;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Package manager (package) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  path [--user USER_ID] PACKAGE");
        pw.println("    Print the path to the .apk of the given PACKAGE.");
        pw.println("");
        pw.println("  dump PACKAGE");
        pw.println("    Print various system state associated with the given PACKAGE.");
        pw.println("");
        pw.println("  dump-package PACKAGE");
        pw.println("    Print package manager state associated with the given PACKAGE.");
        pw.println("");
        pw.println("  has-feature FEATURE_NAME [version]");
        pw.println("    Prints true and returns exit status 0 when system has a FEATURE_NAME,");
        pw.println("    otherwise prints false and returns exit status 1");
        pw.println("");
        pw.println("  list features");
        pw.println("    Prints all features of the system.");
        pw.println("");
        pw.println("  list instrumentation [-f] [TARGET-PACKAGE]");
        pw.println("    Prints all test packages; optionally only those targeting TARGET-PACKAGE");
        pw.println("    Options:");
        pw.println("      -f: dump the name of the .apk file containing the test package");
        pw.println("");
        pw.println("  list libraries [-v]");
        pw.println("    Prints all system libraries.");
        pw.println("    Options:");
        pw.println("      -v: shows the location of the library in the device's filesystem");
        pw.println("");
        pw.println("  list packages [-f] [-d] [-e] [-s] [-q] [-3] [-i] [-l] [-u] [-U] ");
        pw.println("      [--show-versioncode] [--apex-only] [--factory-only]");
        pw.println("      [--uid UID] [--user USER_ID] [FILTER]");
        pw.println("    Prints all packages; optionally only those whose name contains");
        pw.println("    the text in FILTER.  Options are:");
        pw.println("      -f: see their associated file");
        pw.println("      -a: all known packages (but excluding APEXes)");
        pw.println("      -d: filter to only show disabled packages");
        pw.println("      -e: filter to only show enabled packages");
        pw.println("      -s: filter to only show system packages");
        if (Flags.quarantinedEnabled()) {
            pw.println("      -q: filter to only show quarantined packages");
        }
        pw.println("      -3: filter to only show third party packages");
        pw.println("      -i: see the installer for the packages");
        pw.println("      -l: ignored (used for compatibility with older releases)");
        pw.println("      -U: also show the package UID");
        pw.println("      -u: also include uninstalled packages");
        pw.println("      --show-versioncode: also show the version code");
        pw.println("      --apex-only: only show APEX packages");
        pw.println("      --factory-only: only show system packages excluding updates");
        pw.println("      --uid UID: filter to only show packages with the given UID");
        pw.println("      --user USER_ID: only list packages belonging to the given user");
        pw.println("      --match-libraries: include packages that declare static shared and SDK libraries");
        pw.println("");
        pw.println("  list permission-groups");
        pw.println("    Prints all known permission groups.");
        pw.println("");
        pw.println("  list permissions [-g] [-f] [-d] [-u] [GROUP]");
        pw.println("    Prints all known permissions; optionally only those in GROUP.  Options are:");
        pw.println("      -g: organize by group");
        pw.println("      -f: print all information");
        pw.println("      -s: short summary");
        pw.println("      -d: only list dangerous permissions");
        pw.println("      -u: list only the permissions users will see");
        pw.println("");
        pw.println("  list staged-sessions [--only-ready] [--only-sessionid] [--only-parent]");
        pw.println("    Prints all staged sessions.");
        pw.println("      --only-ready: show only staged sessions that are ready");
        pw.println("      --only-sessionid: show only sessionId of each session");
        pw.println("      --only-parent: hide all children sessions");
        pw.println("");
        pw.println("  list users");
        pw.println("    Prints all users.");
        pw.println("");
        pw.println("  resolve-activity [--brief] [--components] [--query-flags FLAGS]");
        pw.println("       [--user USER_ID] INTENT");
        pw.println("    Prints the activity that resolves to the given INTENT.");
        pw.println("");
        pw.println("  query-activities [--brief] [--components] [--query-flags FLAGS]");
        pw.println("       [--user USER_ID] INTENT");
        pw.println("    Prints all activities that can handle the given INTENT.");
        pw.println("");
        pw.println("  query-services [--brief] [--components] [--query-flags FLAGS]");
        pw.println("       [--user USER_ID] INTENT");
        pw.println("    Prints all services that can handle the given INTENT.");
        pw.println("");
        pw.println("  query-receivers [--brief] [--components] [--query-flags FLAGS]");
        pw.println("       [--user USER_ID] INTENT");
        pw.println("    Prints all broadcast receivers that can handle the given INTENT.");
        pw.println("");
        pw.println("  install [-rtfdg] [-i PACKAGE] [--user USER_ID|all|current]");
        pw.println("       [-p INHERIT_PACKAGE] [--install-location 0/1/2]");
        pw.println("       [--install-reason 0/1/2/3/4] [--originating-uri URI]");
        pw.println("       [--referrer URI] [--abi ABI_NAME] [--force-sdk]");
        pw.println("       [--preload] [--instant] [--full] [--dont-kill]");
        pw.println("       [--enable-rollback [0/1/2]]");
        pw.println("       [--force-uuid internal|UUID] [--pkg PACKAGE] [-S BYTES]");
        pw.println("       [--apex] [--non-staged] [--force-non-staged]");
        pw.println("       [--staged-ready-timeout TIMEOUT] [--ignore-dexopt-profile]");
        pw.println("       [--dexopt-compiler-filter FILTER]");
        pw.println("       [PATH [SPLIT...]|-]");
        pw.println("    Install an application.  Must provide the apk data to install, either as");
        pw.println("    file path(s) or '-' to read from stdin.  Options are:");
        pw.println("      -R: disallow replacement of existing application");
        pw.println("      -t: allow test packages");
        pw.println("      -i: specify package name of installer owning the app");
        pw.println("      -f: install application on internal flash");
        pw.println("      -d: allow version code downgrade (debuggable packages only)");
        pw.println("      -p: partial application install (new split on top of existing pkg)");
        pw.println("      -g: grant all runtime permissions");
        pw.println("      -S: size in bytes of package, required for stdin");
        pw.println("      --user: install under the given user.");
        pw.println("      --dont-kill: installing a new feature split, don't kill running app");
        pw.println("      --restrict-permissions: don't whitelist restricted permissions at install");
        pw.println("      --originating-uri: set URI where app was downloaded from");
        pw.println("      --referrer: set URI that instigated the install of the app");
        pw.println("      --pkg: specify expected package name of app being installed");
        pw.println("      --abi: override the default ABI of the platform");
        pw.println("      --instant: cause the app to be installed as an ephemeral install app");
        pw.println("      --full: cause the app to be installed as a non-ephemeral full app");
        pw.println("      --enable-rollback: enable rollbacks for the upgrade.");
        pw.println("          0=restore (default), 1=wipe, 2=retain");
        if (Flags.recoverabilityDetection()) {
            pw.println(
                    "      --rollback-impact-level: set device impact required for rollback.");
            pw.println("          0=low (default), 1=high, 2=manual only");
        }
        pw.println("      --install-location: force the install location:");
        pw.println("          0=auto, 1=internal only, 2=prefer external");
        pw.println("      --install-reason: indicates why the app is being installed:");
        pw.println("          0=unknown, 1=admin policy, 2=device restore,");
        pw.println("          3=device setup, 4=user request");
        pw.println("      --update-ownership: request the update ownership enforcement");
        pw.println("      --force-uuid: force install on to disk volume with given UUID");
        pw.println("      --apex: install an .apex file, not an .apk");
        pw.println("      --non-staged: explicitly set this installation to be non-staged.");
        pw.println("          This flag is only useful for APEX installs that are implicitly");
        pw.println("          assumed to be staged.");
        pw.println("      --force-non-staged: force the installation to run under a non-staged");
        pw.println("          session, which may complete without requiring a reboot. This will");
        pw.println("          force a rebootless update even for APEXes that don't support it");
        pw.println("      --staged-ready-timeout: By default, staged sessions wait "
                + DEFAULT_STAGED_READY_TIMEOUT_MS);
        pw.println("          milliseconds for pre-reboot verification to complete when");
        pw.println("          performing staged install. This flag is used to alter the waiting");
        pw.println("          time. You can skip the waiting time by specifying a TIMEOUT of '0'");
        pw.println("      --ignore-dexopt-profile: if set, all profiles are ignored by dexopt");
        pw.println("          during the installation, including the profile in the DM file and");
        pw.println("          the profile embedded in the APK file. If an invalid profile is");
        pw.println("          provided during installation, no warning will be reported by `adb");
        pw.println("          install`.");
        pw.println("          This option does not affect later dexopt operations (e.g.,");
        pw.println("          background dexopt and manual `pm compile` invocations).");
        pw.println("      --dexopt-compiler-filter: the target compiler filter for dexopt during");
        pw.println("          the installation. The filter actually used may be different.");
        pw.println("          Valid values: one of the values documented in");
        pw.println("          https://source.android.com/docs/core/runtime/configure"
                + "#compiler_filters");
        pw.println("          or 'skip'");
        pw.println("      --force-verification: if set, enable the verification for this install");
        pw.println("");
        pw.println("  install-existing [--user USER_ID|all|current]");
        pw.println("       [--instant] [--full] [--wait] [--restrict-permissions] PACKAGE");
        pw.println("    Installs an existing application for a new user.  Options are:");
        pw.println("      --user: install for the given user.");
        pw.println("      --instant: install as an instant app");
        pw.println("      --full: install as a full app");
        pw.println("      --wait: wait until the package is installed");
        pw.println("      --restrict-permissions: don't whitelist restricted permissions");
        pw.println("");
        pw.println("  install-create [-lrtsfdg] [-i PACKAGE] [--user USER_ID|all|current]");
        pw.println("       [-p INHERIT_PACKAGE] [--install-location 0/1/2]");
        pw.println("       [--install-reason 0/1/2/3/4] [--originating-uri URI]");
        pw.println("       [--referrer URI] [--abi ABI_NAME] [--force-sdk]");
        pw.println("       [--preload] [--instant] [--full] [--dont-kill]");
        pw.println("       [--force-uuid internal|UUID] [--pkg PACKAGE] [--apex] [-S BYTES]");
        pw.println("       [--multi-package] [--staged] [--update-ownership]");
        pw.println("    Like \"install\", but starts an install session.  Use \"install-write\"");
        pw.println("    to push data into the session, and \"install-commit\" to finish.");
        pw.println("");
        pw.println("  install-write [-S BYTES] SESSION_ID SPLIT_NAME [PATH|-]");
        pw.println("    Write an apk into the given install session.  If the path is '-', data");
        pw.println("    will be read from stdin.  Options are:");
        pw.println("      -S: size in bytes of package, required for stdin");
        pw.println("");
        pw.println("  install-remove SESSION_ID SPLIT...");
        pw.println("    Mark SPLIT(s) as removed in the given install session.");
        pw.println("");
        pw.println("  install-add-session MULTI_PACKAGE_SESSION_ID CHILD_SESSION_IDs");
        pw.println("    Add one or more session IDs to a multi-package session.");
        pw.println("");
        pw.println("  install-set-pre-verified-domains SESSION_ID PRE_VERIFIED_DOMAIN... ");
        pw.println("    Specify a comma separated list of pre-verified domains for a session.");
        pw.println("");
        pw.println("  install-get-pre-verified-domains SESSION_ID");
        pw.println("    List all the pre-verified domains that are specified in a session.");
        pw.println("    The result list is comma separated.");
        pw.println("");
        pw.println("  install-commit SESSION_ID");
        pw.println("    Commit the given active install session, installing the app.");
        pw.println("");
        pw.println("  install-abandon SESSION_ID");
        pw.println("    Delete the given active install session.");
        pw.println("");
        pw.println("  set-install-location LOCATION");
        pw.println("    Changes the default install location.  NOTE this is only intended for debugging;");
        pw.println("    using this can cause applications to break and other undersireable behavior.");
        pw.println("    LOCATION is one of:");
        pw.println("    0 [auto]: Let system decide the best location");
        pw.println("    1 [internal]: Install on internal device storage");
        pw.println("    2 [external]: Install on external media");
        pw.println("");
        pw.println("  get-install-location");
        pw.println("    Returns the current install location: 0, 1 or 2 as per set-install-location.");
        pw.println("");
        pw.println("  move-package PACKAGE [internal|UUID]");
        pw.println("");
        pw.println("  move-primary-storage [internal|UUID]");
        pw.println("");
        pw.println("  uninstall [-k] [--user USER_ID] [--versionCode VERSION_CODE]");
        pw.println("       PACKAGE [SPLIT...]");
        pw.println("    Remove the given package name from the system.  May remove an entire app");
        pw.println("    if no SPLIT names specified, otherwise will remove only the splits of the");
        pw.println("    given app.  Options are:");
        pw.println("      -k: keep the data and cache directories around after package removal.");
        pw.println("      --user: remove the app from the given user.");
        pw.println("      --versionCode: only uninstall if the app has the given version code.");
        pw.println("");
        pw.println("  clear [--user USER_ID] [--cache-only] PACKAGE");
        pw.println("    Deletes data associated with a package. Options are:");
        pw.println("    --user: specifies the user for which we need to clear data");
        pw.println("    --cache-only: a flag which tells if we only need to clear cache data");
        pw.println("");
        pw.println("  enable [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  disable [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  disable-user [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  disable-until-used [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  default-state [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("    These commands change the enabled state of a given package or");
        pw.println("    component (written as \"package/class\").");
        pw.println("");
        pw.println("  hide [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("  unhide [--user USER_ID] PACKAGE_OR_COMPONENT");
        pw.println("");
        pw.println("  unstop [--user USER_ID] PACKAGE");
        pw.println("");
        pw.println("  suspend [--user USER_ID] PACKAGE [PACKAGE...]");
        pw.println("    Suspends the specified package(s) (as user).");
        pw.println("");
        pw.println("  unsuspend [--user USER_ID] PACKAGE [PACKAGE...]");
        pw.println("    Unsuspends the specified package(s) (as user).");
        pw.println("");
        pw.println("  set-distracting-restriction [--user USER_ID] [--flag FLAG ...]");
        pw.println("      PACKAGE [PACKAGE...]");
        pw.println("    Sets the specified restriction flags to given package(s) (for user).");
        pw.println("    Flags are:");
        pw.println("      hide-notifications: Hides notifications from this package");
        pw.println("      hide-from-suggestions: Hides this package from suggestions");
        pw.println("        (by the launcher, etc.)");
        pw.println("    Any existing flags are overwritten, which also means that if no flags are");
        pw.println("    specified then all existing flags will be cleared.");
        pw.println("");
        pw.println("  get-distracting-restriction [--user USER_ID] PACKAGE [PACKAGE...]");
        pw.println("    Gets the specified restriction flags of given package(s) (of the user).");
        pw.println("");
        pw.println("  grant [--user USER_ID] [--all-permissions] PACKAGE PERMISSION");
        pw.println("  revoke [--user USER_ID] [--all-permissions] PACKAGE PERMISSION");
        pw.println("    These commands either grant or revoke permissions to apps.  The permissions");
        pw.println("    must be declared as used in the app's manifest, be runtime permissions");
        pw.println("    (protection level dangerous), and the app targeting SDK greater than Lollipop MR1.");
        pw.println("    Flags are:");
        pw.println("    --user: Specifies the user for which the operation needs to be performed");
        pw.println("    --all-permissions: If specified all the missing runtime permissions will");
        pw.println("       be granted to the PACKAGE or to all the packages if none is specified.");
        pw.println("");
        pw.println("  set-permission-flags [--user USER_ID] PACKAGE PERMISSION [FLAGS..]");
        pw.println("  clear-permission-flags [--user USER_ID] PACKAGE PERMISSION [FLAGS..]");
        pw.println("    These commands either set or clear permission flags on apps.  The permissions");
        pw.println("    must be declared as used in the app's manifest, be runtime permissions");
        pw.println("    (protection level dangerous), and the app targeting SDK greater than Lollipop MR1.");
        pw.println("    The flags must be one or more of " + SUPPORTED_PERMISSION_FLAGS_LIST);
        pw.println("");
        pw.println("  reset-permissions");
        pw.println("    Revert all runtime permissions to their default state.");
        pw.println("");
        pw.println("  set-permission-enforced PERMISSION [true|false]");
        pw.println("");
        pw.println("  get-privapp-permissions TARGET-PACKAGE");
        pw.println("    Prints all privileged permissions for a package.");
        pw.println("");
        pw.println("  get-privapp-deny-permissions TARGET-PACKAGE");
        pw.println("    Prints all privileged permissions that are denied for a package.");
        pw.println("");
        pw.println("  get-oem-permissions TARGET-PACKAGE");
        pw.println("    Prints all OEM permissions for a package.");
        pw.println("");
        pw.println("  get-signature-permission-allowlist PARTITION");
        pw.println("    Prints the signature permission allowlist for a partition.");
        pw.println("    PARTITION is one of system, vendor, product, system-ext and apex");
        pw.println("");
        pw.println("  get-shared-uid-allowlist");
        pw.println("    Prints the shared UID allowlist.");
        pw.println("");
        pw.println("  trim-caches DESIRED_FREE_SPACE [internal|UUID]");
        pw.println("    Trim cache files to reach the given free space.");
        pw.println("");
        pw.println("  list users");
        pw.println("    Lists the current users.");
        pw.println("");
        pw.println("  create-user [--profileOf USER_ID] [--managed] [--restricted] [--guest]");
        pw.println("       [--user-type USER_TYPE] [--ephemeral] [--for-testing] [--pre-create-only]   USER_NAME");
        pw.println("    Create a new user with the given USER_NAME, printing the new user identifier");
        pw.println("    of the user.");
        // TODO(b/142482943): Consider fetching the list of user types from UMS.
        pw.println("    USER_TYPE is the name of a user type, e.g. android.os.usertype.profile.MANAGED.");
        pw.println("      If not specified, the default user type is android.os.usertype.full.SECONDARY.");
        pw.println("      --managed is shorthand for '--user-type android.os.usertype.profile.MANAGED'.");
        pw.println("      --restricted is shorthand for '--user-type android.os.usertype.full.RESTRICTED'.");
        pw.println("      --guest is shorthand for '--user-type android.os.usertype.full.GUEST'.");
        pw.println("");
        pw.println("  remove-user [--set-ephemeral-if-in-use | --wait] USER_ID");
        pw.println("    Remove the user with the given USER_IDENTIFIER, deleting all data");
        pw.println("    associated with that user.");
        pw.println("      --set-ephemeral-if-in-use: If the user is currently running and");
        pw.println("        therefore cannot be removed immediately, mark the user as ephemeral");
        pw.println("        so that it will be automatically removed when possible (after user");
        pw.println("        switch or reboot)");
        pw.println("      --wait: Wait until user is removed. Ignored if set-ephemeral-if-in-use");
        pw.println("");
        pw.println("  mark-guest-for-deletion USER_ID");
        pw.println("    Mark the guest user for deletion. After this, it is possible to create a");
        pw.println("    new guest user and switch to it. This allows resetting the guest user");
        pw.println("    without switching to another user.");
        pw.println("");
        pw.println("  rename-user USER_ID [USER_NAME]");
        pw.println("    Rename USER_ID with USER_NAME (or null when [USER_NAME] is not set)");
        pw.println("");
        pw.println("  set-user-restriction [--user USER_ID] RESTRICTION VALUE");
        pw.println("");
        pw.println("  get-user-restriction [--user USER_ID] [--all] RESTRICTION_KEY");
        pw.println("    Display the value of restriction for the given restriction key if the");
        pw.println("    given user is valid.");
        pw.println("      --all: display all restrictions for the given user");
        pw.println("          This option is used without restriction key");
        pw.println("");
        pw.println("  get-max-users");
        pw.println("");
        pw.println("  get-max-running-users");
        pw.println("");
        pw.println("  set-home-activity [--user USER_ID] TARGET-COMPONENT");
        pw.println("    Set the default home activity (aka launcher).");
        pw.println("    TARGET-COMPONENT can be a package name (com.package.my) or a full");
        pw.println("    component (com.package.my/component.name). However, only the package name");
        pw.println("    matters: the actual component used will be determined automatically from");
        pw.println("    the package.");
        pw.println("");
        pw.println("  set-installer PACKAGE INSTALLER");
        pw.println("    Set installer package name");
        pw.println("");
        pw.println("  get-instantapp-resolver");
        pw.println(
                "    Return the name of the component that is the current instant app installer.");
        pw.println("");
        pw.println("  set-harmful-app-warning [--user <USER_ID>] <PACKAGE> [<WARNING>]");
        pw.println("    Mark the app as harmful with the given warning message.");
        pw.println("");
        pw.println("  get-harmful-app-warning [--user <USER_ID>] <PACKAGE>");
        pw.println("    Return the harmful app warning message for the given app, if present");
        pw.println();
        pw.println("  uninstall-system-updates [<PACKAGE>]");
        pw.println("    Removes updates to the given system application and falls back to its");
        pw.println("    /system version. Does nothing if the given package is not a system app.");
        pw.println("    If no package is specified, removes updates to all system applications.");
        pw.println("");
        pw.println("  get-moduleinfo [--all | --installed] [module-name]");
        pw.println("    Displays module info. If module-name is specified only that info is shown");
        pw.println("    By default, without any argument only installed modules are shown.");
        pw.println("      --all: show all module info");
        pw.println("      --installed: show only installed modules");
        pw.println("");
        pw.println("  log-visibility [--enable|--disable] <PACKAGE>");
        pw.println("    Turns on debug logging when visibility is blocked for the given package.");
        pw.println("      --enable: turn on debug logging (default)");
        pw.println("      --disable: turn off debug logging");
        pw.println("");
        pw.println("  set-silent-updates-policy [--allow-unlimited-silent-updates <INSTALLER>]");
        pw.println("                            [--throttle-time <SECONDS>] [--reset]");
        pw.println("    Sets the policies of the silent updates.");
        pw.println("      --allow-unlimited-silent-updates: allows unlimited silent updated");
        pw.println("        installation requests from the installer without the throttle time.");
        pw.println("      --throttle-time: update the silent updates throttle time in seconds.");
        pw.println("      --reset: restore the installer and throttle time to the default, and");
        pw.println("        clear tracks of silent updates in the system.");
        pw.println("");
        pw.println("  clear-package-preferred-activities <PACKAGE>");
        pw.println("    Remove the preferred activity mappings for the given package.");
        pw.println("  wait-for-handler --timeout <MILLIS>");
        pw.println("    Wait for a given amount of time till the package manager handler finishes");
        pw.println("    handling all pending messages.");
        pw.println("      --timeout: wait for a given number of milliseconds. If the handler(s)");
        pw.println("        fail to finish before the timeout, the command returns error.");
        pw.println("");
        pw.println("  wait-for-background-handler --timeout <MILLIS>");
        pw.println("    Wait for a given amount of time till the package manager's background");
        pw.println("    handler finishes handling all pending messages.");
        pw.println("      --timeout: wait for a given number of milliseconds. If the handler(s)");
        pw.println("        fail to finish before the timeout, the command returns error.");
        pw.println("");
        pw.println("  archive [--user USER_ID] PACKAGE ");
        pw.println("    During the archival process, the apps APKs and cache are removed from the");
        pw.println("    device while the user data is kept. Options are:");
        pw.println("      --user: archive the app from the given user.");
        pw.println("");
        pw.println("  request-unarchive [--user USER_ID] PACKAGE ");
        pw.println("    Requests to unarchive a currently archived package by sending a request");
        pw.println("    to unarchive an app to the responsible installer. Options are:");
        pw.println("      --user: request unarchival of the app from the given user.");
        pw.println("");
        pw.println("  get-domain-verification-agent [--user USER_ID]");
        pw.println("    Displays the component name of the domain verification agent on device.");
        pw.println("    If the component isn't enabled, an error message will be displayed.");
        pw.println("      --user: return the agent of the given user (SYSTEM_USER if unspecified)");
        pw.println("  get-package-storage-stats [--user <USER_ID>] <PACKAGE>");
        pw.println("    Return the storage stats for the given app, if present");
        pw.println("  get-verification-policy [--user USER_ID]");
        pw.println("    Display current verification enforcement policy which will be applied to");
        pw.println("    all the future installation sessions");
        pw.println("      --user: show the policy of the given user (SYSTEM_USER if unspecified)");
        pw.println("  set-verification-policy POLICY [--user USER_ID]");
        pw.println("    Sets the verification policy of all the future installation sessions.");
        pw.println("      --user: set the policy of the given user (SYSTEM_USER if unspecified)");
        pw.println("");
        pw.println("");
        printArtServiceHelp();
        pw.println("");
        mDomainVerificationShell.printHelp(pw);
        pw.println("");
        Intent.printIntentArgsHelp(pw, "");
    }

    private void printArtServiceHelp() {
        final var ipw = new IndentingPrintWriter(getOutPrintWriter(), "  " /* singleIndent */);
        ipw.increaseIndent();
        try {
            LocalManagerRegistry.getManagerOrThrow(ArtManagerLocal.class)
                    .printShellCommandHelp(ipw);
        } catch (ManagerNotFoundException e) {
            ipw.println("ART Service is not ready. Please try again later");
        }
        ipw.decreaseIndent();
    }

    private static class LocalIntentReceiver {
        private final LinkedBlockingQueue<Intent> mResult = new LinkedBlockingQueue<>();

        private final IIntentSender.Stub mLocalSender = new IIntentSender.Stub() {
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
}
