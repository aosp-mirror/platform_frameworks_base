/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.apex.IApexService;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.parsing.PackageInfoWithoutStateUtils;
import android.content.pm.parsing.ParsingPackageUtils;
import android.os.Binder;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.sysprop.ApexProperties;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Singleton;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.pm.parsing.PackageParser2;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.utils.TimingsTraceAndSlog;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * ApexManager class handles communications with the apex service to perform operation and queries,
 * as well as providing caching to avoid unnecessary calls to the service.
 */
public abstract class ApexManager {

    private static final String TAG = "ApexManager";

    public static final int MATCH_ACTIVE_PACKAGE = 1 << 0;
    static final int MATCH_FACTORY_PACKAGE = 1 << 1;

    private static final Singleton<ApexManager> sApexManagerSingleton =
            new Singleton<ApexManager>() {
                @Override
                protected ApexManager create() {
                    if (ApexProperties.updatable().orElse(false)) {
                        return new ApexManagerImpl();
                    } else {
                        return new ApexManagerFlattenedApex();
                    }
                }
            };

    /**
     * Returns an instance of either {@link ApexManagerImpl} or {@link ApexManagerFlattenedApex}
     * depending on whether this device supports APEX, i.e. {@link ApexProperties#updatable()}
     * evaluates to {@code true}.
     * @hide
     */
    public static ApexManager getInstance() {
        return sApexManagerSingleton.get();
    }

    /**
     * Minimal information about APEX mount points and the original APEX package they refer to.
     * @hide
     */
    public static class ActiveApexInfo {
        @Nullable public final String apexModuleName;
        public final File apexDirectory;
        public final File preInstalledApexPath;

        private ActiveApexInfo(File apexDirectory, File preInstalledApexPath) {
            this(null, apexDirectory, preInstalledApexPath);
        }

        private ActiveApexInfo(@Nullable String apexModuleName, File apexDirectory,
                File preInstalledApexPath) {
            this.apexModuleName = apexModuleName;
            this.apexDirectory = apexDirectory;
            this.preInstalledApexPath = preInstalledApexPath;
        }

        private ActiveApexInfo(ApexInfo apexInfo) {
            this(
                    apexInfo.moduleName,
                    new File(Environment.getApexDirectory() + File.separator
                            + apexInfo.moduleName),
                    new File(apexInfo.preinstalledModulePath));
        }
    }

    /**
     * Returns {@link ActiveApexInfo} records relative to all active APEX packages.
     *
     * @hide
     */
    public abstract List<ActiveApexInfo> getActiveApexInfos();

    /**
     * Called by package manager service to scan apex package files when device boots up.
     *
     * @param packageParser The package parser to support apex package parsing and caching parsed
     *                      results.
     * @param executorService An executor to support parallel package parsing.
     */
    abstract void scanApexPackagesTraced(@NonNull PackageParser2 packageParser,
            @NonNull ExecutorService executorService);

    /**
     * Retrieves information about an APEX package.
     *
     * @param packageName the package name to look for. Note that this is the package name reported
     *                    in the APK container manifest (i.e. AndroidManifest.xml), which might
     *                    differ from the one reported in the APEX manifest (i.e.
     *                    apex_manifest.json).
     * @param flags the type of package to return. This may match to active packages
     *              and factory (pre-installed) packages.
     * @return a PackageInfo object with the information about the package, or null if the package
     *         is not found.
     */
    @Nullable
    public abstract PackageInfo getPackageInfo(String packageName, @PackageInfoFlags int flags);

    /**
     * Retrieves information about all active APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         active package.
     */
    abstract List<PackageInfo> getActivePackages();

    /**
     * Retrieves information about all active pre-installed APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         active pre-installed package.
     */
    abstract List<PackageInfo> getFactoryPackages();

    /**
     * Retrieves information about all inactive APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         inactive package.
     */
    abstract List<PackageInfo> getInactivePackages();

    /**
     * Checks if {@code packageName} is an apex package.
     *
     * @param packageName package to check.
     * @return {@code true} if {@code packageName} is an apex package.
     */
    abstract boolean isApexPackage(String packageName);

    /**
     * Whether the APEX package is pre-installed or not.
     *
     * @param packageInfo the package to check
     * @return {@code true} if this package is pre-installed, {@code false} otherwise.
     */
    public static boolean isFactory(@NonNull PackageInfo packageInfo) {
        return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * Returns the active apex package's name that contains the (apk) package.
     *
     * @param containedPackage The (apk) package that might be in a apex
     * @return the apex package's name of {@code null} if the {@code containedPackage} is not inside
     *         any apex.
     */
    @Nullable
    public abstract String getActiveApexPackageNameContainingPackage(
            @NonNull AndroidPackage containedPackage);

    /**
     * Retrieves information about an apexd staged session i.e. the internal state used by apexd to
     * track the different states of a session.
     *
     * @param sessionId the identifier of the session.
     * @return an ApexSessionInfo object, or null if the session is not known.
     */
    @Nullable
    abstract ApexSessionInfo getStagedSessionInfo(int sessionId);

    /**
     * Submit a staged session to apex service. This causes the apex service to perform some initial
     * verification and accept or reject the session. Submitting a session successfully is not
     * enough for it to be activated at the next boot, the caller needs to call
     * {@link #markStagedSessionReady(int)}.
     *
     * @throws PackageManagerException if call to apexd fails
     */
    abstract ApexInfoList submitStagedSession(ApexSessionParams params)
            throws PackageManagerException;

    /**
     * Mark a staged session previously submitted using {@code submitStagedSession} as ready to be
     * applied at next reboot.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as ready.
     * @throws PackageManagerException if call to apexd fails
     */
    abstract void markStagedSessionReady(int sessionId) throws PackageManagerException;

    /**
     * Marks a staged session as successful.
     *
     * <p>Only activated session can be marked as successful.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as
     *                  successful.
     */
    abstract void markStagedSessionSuccessful(int sessionId);

    /**
     * Whether the current device supports the management of APEX packages.
     *
     * @return true if APEX packages can be managed on this device, false otherwise.
     */
    abstract boolean isApexSupported();

    /**
     * Abandons the (only) active session previously submitted.
     *
     * @return {@code true} upon success, {@code false} if any remote exception occurs
     */
    abstract boolean revertActiveSessions();

    /**
     * Abandons the staged session with the given sessionId.
     *
     * @return {@code true} upon success, {@code false} if any remote exception occurs
     */
    abstract boolean abortStagedSession(int sessionId) throws PackageManagerException;

    /**
     * Uninstalls given {@code apexPackage}.
     *
     * <p>NOTE. Device must be rebooted in order for uninstall to take effect.
     *
     * @param apexPackagePath package to uninstall.
     * @return {@code true} upon successful uninstall, {@code false} otherwise.
     */
    abstract boolean uninstallApex(String apexPackagePath);

    /**
     * Registers an APK package as an embedded apk of apex.
     */
    abstract void registerApkInApex(AndroidPackage pkg);

    /**
     * Reports error raised during installation of apk-in-apex.
     *
     * @param scanDir the directory of the apex inside which apk-in-apex resides.
     */
    abstract void reportErrorWithApkInApex(String scanDirPath);

    /**
     * Returns true if there were no errors when installing apk-in-apex inside
     * {@param apexPackageName}, otherwise false.
     *
     * @param apexPackageName Package name of the apk container of apex
     */
    abstract boolean isApkInApexInstallSuccess(String apexPackageName);

    /**
     * Returns list of {@code packageName} of apks inside the given apex.
     * @param apexPackageName Package name of the apk container of apex
     */
    abstract List<String> getApksInApex(String apexPackageName);

    /**
     * Returns the apex module name for the given package name, if the package is an APEX. Otherwise
     * returns {@code null}.
     */
    @Nullable
    public abstract String getApexModuleNameForPackageName(String apexPackageName);

    /**
     * Copies the CE apex data directory for the given {@code userId} to a backup location, for use
     * in case of rollback.
     *
     * @return long inode for the snapshot directory if the snapshot was successful, or -1 if not
     */
    public abstract long snapshotCeData(int userId, int rollbackId, String apexPackageName);

    /**
     * Restores the snapshot of the CE apex data directory for the given {@code userId}.
     *
     * @return boolean true if the restore was successful
     */
    public abstract boolean restoreCeData(int userId, int rollbackId, String apexPackageName);

    /**
     * Deletes snapshots of the device encrypted apex data directories for the given
     * {@code rollbackId}.
     *
     * @return boolean true if the delete was successful
     */
    public abstract boolean destroyDeSnapshots(int rollbackId);

    /**
     * Deletes snapshots of the credential encrypted apex data directories for the specified user,
     * where the rollback id is not included in {@code retainRollbackIds}.
     *
     * @return boolean true if the delete was successful
     */
    public abstract boolean destroyCeSnapshotsNotSpecified(int userId, int[] retainRollbackIds);

    /**
     * Dumps various state information to the provided {@link PrintWriter} object.
     *
     * @param pw the {@link PrintWriter} object to send information to.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set, only
     *                    information about that specific package will be dumped.
     */
    abstract void dump(PrintWriter pw, @Nullable String packageName);

    @IntDef(
            flag = true,
            prefix = { "MATCH_"},
            value = {MATCH_ACTIVE_PACKAGE, MATCH_FACTORY_PACKAGE})
    @Retention(RetentionPolicy.SOURCE)
    @interface PackageInfoFlags{}

    /**
     * An implementation of {@link ApexManager} that should be used in case device supports updating
     * APEX packages.
     */
    @VisibleForTesting
    protected static class ApexManagerImpl extends ApexManager {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        private Set<ActiveApexInfo> mActiveApexInfosCache;

        /**
         * Contains the list of {@code packageName}s of apks-in-apex for given
         * {@code apexModuleName}. See {@link #mPackageNameToApexModuleName} to understand the
         * difference between {@code packageName} and {@code apexModuleName}.
         */
        @GuardedBy("mLock")
        private ArrayMap<String, List<String>> mApksInApex = new ArrayMap<>();

        /**
         * Contains the list of {@code Exception}s that were raised when installing apk-in-apex
         * inside {@code apexModuleName}.
         */
        @GuardedBy("mLock")
        private Set<String> mErrorWithApkInApex = new ArraySet<>();

        @GuardedBy("mLock")
        private List<PackageInfo> mAllPackagesCache;

        /**
         * An APEX is a file format that delivers the apex-payload wrapped in an apk container. The
         * apk container has a reference name, called {@code packageName}, which is found inside the
         * {@code AndroidManifest.xml}. The apex payload inside the container also has a reference
         * name, called {@code apexModuleName}, which is found in {@code apex_manifest.json} file.
         *
         * {@link #mPackageNameToApexModuleName} contains the mapping from {@code packageName} of
         * the apk container to {@code apexModuleName} of the apex-payload inside.
         */
        @GuardedBy("mLock")
        private ArrayMap<String, String> mPackageNameToApexModuleName;

        /**
         * Whether an APEX package is active or not.
         *
         * @param packageInfo the package to check
         * @return {@code true} if this package is active, {@code false} otherwise.
         */
        private static boolean isActive(PackageInfo packageInfo) {
            return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0;
        }

        /**
         * Retrieve the service from ServiceManager. If the service is not running, it will be
         * started, and this function will block until it is ready.
         */
        @VisibleForTesting
        protected IApexService waitForApexService() {
            // Since apexd is a trusted platform component, synchronized calls are allowable
            return IApexService.Stub.asInterface(
                    Binder.allowBlocking(ServiceManager.waitForService("apexservice")));
        }

        @Override
        public List<ActiveApexInfo> getActiveApexInfos() {
            final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                    Trace.TRACE_TAG_APEX_MANAGER);
            synchronized (mLock) {
                if (mActiveApexInfosCache == null) {
                    t.traceBegin("getActiveApexInfos_noCache");
                    try {
                        mActiveApexInfosCache = new ArraySet<>();
                        final ApexInfo[] activePackages = waitForApexService().getActivePackages();
                        for (int i = 0; i < activePackages.length; i++) {
                            ApexInfo apexInfo = activePackages[i];
                            mActiveApexInfosCache.add(new ActiveApexInfo(apexInfo));
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Unable to retrieve packages from apexservice", e);
                    }
                    t.traceEnd();
                }
                if (mActiveApexInfosCache != null) {
                    return new ArrayList<>(mActiveApexInfosCache);
                } else {
                    return Collections.emptyList();
                }
            }
        }

        @Override
        void scanApexPackagesTraced(@NonNull PackageParser2 packageParser,
                @NonNull ExecutorService executorService) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "scanApexPackagesTraced");
            try {
                synchronized (mLock) {
                    scanApexPackagesInternalLocked(packageParser, executorService);
                }
            } finally {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }

        @GuardedBy("mLock")
        private void scanApexPackagesInternalLocked(PackageParser2 packageParser,
                ExecutorService executorService) {
            final ApexInfo[] allPkgs;
            try {
                mAllPackagesCache = new ArrayList<>();
                mPackageNameToApexModuleName = new ArrayMap<>();
                allPkgs = waitForApexService().getAllPackages();
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
                throw new RuntimeException(re);
            }
            if (allPkgs.length == 0) {
                return;
            }
            final int flags = PackageManager.GET_META_DATA
                    | PackageManager.GET_SIGNING_CERTIFICATES
                    | PackageManager.GET_SIGNATURES;
            ArrayMap<File, ApexInfo> parsingApexInfo = new ArrayMap<>();
            ParallelPackageParser parallelPackageParser =
                    new ParallelPackageParser(packageParser, executorService);

            for (ApexInfo ai : allPkgs) {
                File apexFile = new File(ai.modulePath);
                parallelPackageParser.submit(apexFile, 0);
                parsingApexInfo.put(apexFile, ai);
            }

            HashSet<String> activePackagesSet = new HashSet<>();
            HashSet<String> factoryPackagesSet = new HashSet<>();
            // Process results one by one
            for (int i = 0; i < parsingApexInfo.size(); i++) {
                ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
                Throwable throwable = parseResult.throwable;
                ApexInfo ai = parsingApexInfo.get(parseResult.scanFile);

                if (throwable == null) {
                    // Unfortunately, ParallelPackageParser won't collect certificates for us. We
                    // need to manually collect them here.
                    ParsedPackage pp = parseResult.parsedPackage;
                    try {
                        pp.setSigningDetails(
                                ParsingPackageUtils.collectCertificates(pp, false));
                    } catch (PackageParserException e) {
                        throw new IllegalStateException(
                                "Unable to collect certificates for " + ai.modulePath, e);
                    }
                    final PackageInfo packageInfo = PackageInfoWithoutStateUtils.generate(
                            pp, ai, flags);
                    if (packageInfo == null) {
                        throw new IllegalStateException("Unable to generate package info: "
                                + ai.modulePath);
                    }
                    mAllPackagesCache.add(packageInfo);
                    mPackageNameToApexModuleName.put(packageInfo.packageName, ai.moduleName);
                    if (ai.isActive) {
                        if (activePackagesSet.contains(packageInfo.packageName)) {
                            throw new IllegalStateException(
                                    "Two active packages have the same name: "
                                            + packageInfo.packageName);
                        }
                        activePackagesSet.add(packageInfo.packageName);
                    }
                    if (ai.isFactory) {
                        if (factoryPackagesSet.contains(packageInfo.packageName)) {
                            throw new IllegalStateException(
                                    "Two factory packages have the same name: "
                                            + packageInfo.packageName);
                        }
                        factoryPackagesSet.add(packageInfo.packageName);
                    }
                } else if (throwable instanceof PackageParserException) {
                    final PackageParserException e = (PackageParserException) throwable;
                    // Skip parsing non-coreApp apex file if system is in minimal boot state.
                    if (e.error == PackageManager.INSTALL_PARSE_FAILED_ONLY_COREAPP_ALLOWED) {
                        Slog.w(TAG, "Scan apex failed, not a coreApp:" + ai.modulePath);
                        continue;
                    }
                    throw new IllegalStateException("Unable to parse: " + ai.modulePath, throwable);
                } else {
                    throw new IllegalStateException("Unexpected exception occurred while parsing "
                            + ai.modulePath, throwable);
                }
            }
        }

        @Override
        @Nullable
        public PackageInfo getPackageInfo(String packageName,
                @PackageInfoFlags int flags) {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            boolean matchActive = (flags & MATCH_ACTIVE_PACKAGE) != 0;
            boolean matchFactory = (flags & MATCH_FACTORY_PACKAGE) != 0;
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (!packageInfo.packageName.equals(packageName)) {
                    continue;
                }
                if ((matchActive && isActive(packageInfo))
                        || (matchFactory && isFactory(packageInfo))) {
                    return packageInfo;
                }
            }
            return null;
        }

        @Override
        List<PackageInfo> getActivePackages() {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<PackageInfo> activePackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (isActive(packageInfo)) {
                    activePackages.add(packageInfo);
                }
            }
            return activePackages;
        }

        @Override
        List<PackageInfo> getFactoryPackages() {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<PackageInfo> factoryPackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (isFactory(packageInfo)) {
                    factoryPackages.add(packageInfo);
                }
            }
            return factoryPackages;
        }

        @Override
        List<PackageInfo> getInactivePackages() {
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            final List<PackageInfo> inactivePackages = new ArrayList<>();
            for (int i = 0; i < mAllPackagesCache.size(); i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (!isActive(packageInfo)) {
                    inactivePackages.add(packageInfo);
                }
            }
            return inactivePackages;
        }

        @Override
        boolean isApexPackage(String packageName) {
            if (!isApexSupported()) return false;
            Preconditions.checkState(mAllPackagesCache != null,
                    "APEX packages have not been scanned");
            for (int i = 0, size = mAllPackagesCache.size(); i < size; i++) {
                final PackageInfo packageInfo = mAllPackagesCache.get(i);
                if (packageInfo.packageName.equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        @Nullable
        public String getActiveApexPackageNameContainingPackage(
                @NonNull AndroidPackage containedPackage) {
            Preconditions.checkState(mPackageNameToApexModuleName != null,
                    "APEX packages have not been scanned");

            Objects.requireNonNull(containedPackage);

            synchronized (mLock) {
                int numApksInApex = mApksInApex.size();
                for (int apkInApexNum = 0; apkInApexNum < numApksInApex; apkInApexNum++) {
                    if (mApksInApex.valueAt(apkInApexNum).contains(
                            containedPackage.getPackageName())) {
                        String apexModuleName = mApksInApex.keyAt(apkInApexNum);

                        int numApexPkgs = mPackageNameToApexModuleName.size();
                        for (int apexPkgNum = 0; apexPkgNum < numApexPkgs; apexPkgNum++) {
                            if (mPackageNameToApexModuleName.valueAt(apexPkgNum).equals(
                                    apexModuleName)) {
                                return mPackageNameToApexModuleName.keyAt(apexPkgNum);
                            }
                        }
                    }
                }
            }

            return null;
        }

        @Override
        @Nullable ApexSessionInfo getStagedSessionInfo(int sessionId) {
            try {
                ApexSessionInfo apexSessionInfo =
                        waitForApexService().getStagedSessionInfo(sessionId);
                if (apexSessionInfo.isUnknown) {
                    return null;
                }
                return apexSessionInfo;
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                throw new RuntimeException(re);
            }
        }

        @Override
        ApexInfoList submitStagedSession(ApexSessionParams params) throws PackageManagerException {
            try {
                final ApexInfoList apexInfoList = new ApexInfoList();
                waitForApexService().submitStagedSession(params, apexInfoList);
                return apexInfoList;
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                throw new RuntimeException(re);
            } catch (Exception e) {
                throw new PackageManagerException(
                        PackageInstaller.SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                        "apexd verification failed : " + e.getMessage());
            }
        }

        @Override
        void markStagedSessionReady(int sessionId) throws PackageManagerException {
            try {
                waitForApexService().markStagedSessionReady(sessionId);
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                throw new RuntimeException(re);
            } catch (Exception e) {
                throw new PackageManagerException(
                        PackageInstaller.SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                        "Failed to mark apexd session as ready : " + e.getMessage());
            }
        }

        @Override
        void markStagedSessionSuccessful(int sessionId) {
            try {
                waitForApexService().markStagedSessionSuccessful(sessionId);
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                throw new RuntimeException(re);
            } catch (Exception e) {
                // It is fine to just log an exception in this case. APEXd will be able to recover
                // in case markStagedSessionSuccessful fails.
                Slog.e(TAG, "Failed to mark session " + sessionId + " as successful", e);
            }
        }

        @Override
        boolean isApexSupported() {
            return true;
        }

        @Override
        boolean revertActiveSessions() {
            try {
                waitForApexService().revertActiveSessions();
                return true;
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                return false;
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return false;
            }
        }

        @Override
        boolean abortStagedSession(int sessionId) throws PackageManagerException {
            try {
                waitForApexService().abortStagedSession(sessionId);
                return true;
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                return false;
            } catch (Exception e) {
                throw new PackageManagerException(
                        PackageInstaller.SessionInfo.STAGED_SESSION_VERIFICATION_FAILED,
                        "Failed to abort staged session : " + e.getMessage());
            }
        }

        @Override
        boolean uninstallApex(String apexPackagePath) {
            try {
                waitForApexService().unstagePackages(Collections.singletonList(apexPackagePath));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        void registerApkInApex(AndroidPackage pkg) {
            synchronized (mLock) {
                for (ActiveApexInfo aai : mActiveApexInfosCache) {
                    if (pkg.getBaseCodePath().startsWith(aai.apexDirectory.getAbsolutePath())) {
                        List<String> apks = mApksInApex.get(aai.apexModuleName);
                        if (apks == null) {
                            apks = Lists.newArrayList();
                            mApksInApex.put(aai.apexModuleName, apks);
                        }
                        apks.add(pkg.getPackageName());
                    }
                }
            }
        }

        @Override
        void reportErrorWithApkInApex(String scanDirPath) {
            synchronized (mLock) {
                for (ActiveApexInfo aai : mActiveApexInfosCache) {
                    if (scanDirPath.startsWith(aai.apexDirectory.getAbsolutePath())) {
                        mErrorWithApkInApex.add(aai.apexModuleName);
                    }
                }
            }
        }

        @Override
        boolean isApkInApexInstallSuccess(String apexPackageName) {
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                String moduleName = mPackageNameToApexModuleName.get(apexPackageName);
                if (moduleName == null) {
                    return false;
                }
                return !mErrorWithApkInApex.contains(moduleName);
            }
        }

        @Override
        List<String> getApksInApex(String apexPackageName) {
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                String moduleName = mPackageNameToApexModuleName.get(apexPackageName);
                if (moduleName == null) {
                    return Collections.emptyList();
                }
                return mApksInApex.getOrDefault(moduleName, Collections.emptyList());
            }
        }

        @Override
        @Nullable
        public String getApexModuleNameForPackageName(String apexPackageName) {
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                return mPackageNameToApexModuleName.get(apexPackageName);
            }
        }

        @Override
        public long snapshotCeData(int userId, int rollbackId, String apexPackageName) {
            String apexModuleName;
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                apexModuleName = mPackageNameToApexModuleName.get(apexPackageName);
            }
            if (apexModuleName == null) {
                Slog.e(TAG, "Invalid apex package name: " + apexPackageName);
                return -1;
            }
            try {
                return waitForApexService().snapshotCeData(userId, rollbackId, apexModuleName);
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return -1;
            }
        }

        @Override
        public boolean restoreCeData(int userId, int rollbackId, String apexPackageName) {
            String apexModuleName;
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                apexModuleName = mPackageNameToApexModuleName.get(apexPackageName);
            }
            if (apexModuleName == null) {
                Slog.e(TAG, "Invalid apex package name: " + apexPackageName);
                return false;
            }
            try {
                waitForApexService().restoreCeData(userId, rollbackId, apexModuleName);
                return true;
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return false;
            }
        }

        @Override
        public boolean destroyDeSnapshots(int rollbackId) {
            try {
                waitForApexService().destroyDeSnapshots(rollbackId);
                return true;
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return false;
            }
        }

        @Override
        public boolean destroyCeSnapshotsNotSpecified(int userId, int[] retainRollbackIds) {
            try {
                waitForApexService().destroyCeSnapshotsNotSpecified(userId, retainRollbackIds);
                return true;
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return false;
            }
        }

        /**
         * Dump information about the packages contained in a particular cache
         * @param packagesCache the cache to print information about.
         * @param packageName a {@link String} containing a package name, or {@code null}. If set,
         *                    only information about that specific package will be dumped.
         * @param ipw the {@link IndentingPrintWriter} object to send information to.
         */
        void dumpFromPackagesCache(
                List<PackageInfo> packagesCache,
                @Nullable String packageName,
                IndentingPrintWriter ipw) {
            ipw.println();
            ipw.increaseIndent();
            for (int i = 0, size = packagesCache.size(); i < size; i++) {
                final PackageInfo pi = packagesCache.get(i);
                if (packageName != null && !packageName.equals(pi.packageName)) {
                    continue;
                }
                ipw.println(pi.packageName);
                ipw.increaseIndent();
                ipw.println("Version: " + pi.versionCode);
                ipw.println("Path: " + pi.applicationInfo.sourceDir);
                ipw.println("IsActive: " + isActive(pi));
                ipw.println("IsFactory: " + isFactory(pi));
                ipw.decreaseIndent();
            }
            ipw.decreaseIndent();
            ipw.println();
        }

        @Override
        void dump(PrintWriter pw, @Nullable String packageName) {
            final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ", 120);
            try {
                ipw.println();
                ipw.println("APEX session state:");
                ipw.increaseIndent();
                final ApexSessionInfo[] sessions = waitForApexService().getSessions();
                for (ApexSessionInfo si : sessions) {
                    ipw.println("Session ID: " + si.sessionId);
                    ipw.increaseIndent();
                    if (si.isUnknown) {
                        ipw.println("State: UNKNOWN");
                    } else if (si.isVerified) {
                        ipw.println("State: VERIFIED");
                    } else if (si.isStaged) {
                        ipw.println("State: STAGED");
                    } else if (si.isActivated) {
                        ipw.println("State: ACTIVATED");
                    } else if (si.isActivationFailed) {
                        ipw.println("State: ACTIVATION FAILED");
                    } else if (si.isSuccess) {
                        ipw.println("State: SUCCESS");
                    } else if (si.isRevertInProgress) {
                        ipw.println("State: REVERT IN PROGRESS");
                    } else if (si.isReverted) {
                        ipw.println("State: REVERTED");
                    } else if (si.isRevertFailed) {
                        ipw.println("State: REVERT FAILED");
                    }
                    ipw.decreaseIndent();
                }
                ipw.decreaseIndent();
                ipw.println();
                if (mAllPackagesCache == null) {
                    ipw.println("APEX packages have not been scanned");
                    return;
                }
                ipw.println("Active APEX packages:");
                dumpFromPackagesCache(getActivePackages(), packageName, ipw);
                ipw.println("Inactive APEX packages:");
                dumpFromPackagesCache(getInactivePackages(), packageName, ipw);
                ipw.println("Factory APEX packages:");
                dumpFromPackagesCache(getFactoryPackages(), packageName, ipw);
            } catch (RemoteException e) {
                ipw.println("Couldn't communicate with apexd.");
            }
        }
    }

    /**
     * An implementation of {@link ApexManager} that should be used in case device does not support
     * updating APEX packages.
     */
    private static final class ApexManagerFlattenedApex extends ApexManager {
        @Override
        public List<ActiveApexInfo> getActiveApexInfos() {
            // There is no apexd running in case of flattened apex
            // We look up the /apex directory and identify the active APEX modules from there.
            // As "preinstalled" path, we just report /system since in the case of flattened APEX
            // the /apex directory is just a symlink to /system/apex.
            List<ActiveApexInfo> result = new ArrayList<>();
            File apexDir = Environment.getApexDirectory();
            // In flattened configuration, init special-case the art directory and bind-mounts
            // com.android.art.{release|debug} to com.android.art. At the time of writing, these
            // directories are copied from the kArtApexDirNames variable in
            // system/core/init/mount_namespace.cpp.
            String[] skipDirs = {"com.android.art.release", "com.android.art.debug"};
            if (apexDir.isDirectory()) {
                File[] files = apexDir.listFiles();
                // listFiles might be null if system server doesn't have permission to read
                // a directory.
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory() && !file.getName().contains("@")) {
                            for (String skipDir : skipDirs) {
                                if (file.getName().equals(skipDir)) {
                                    continue;
                                }
                            }
                            result.add(new ActiveApexInfo(file, Environment.getRootDirectory()));
                        }
                    }
                }
            }
            return result;
        }

        @Override
        void scanApexPackagesTraced(@NonNull PackageParser2 packageParser,
                @NonNull ExecutorService executorService) {
            // No-op
        }

        @Override
        public PackageInfo getPackageInfo(String packageName, int flags) {
            return null;
        }

        @Override
        List<PackageInfo> getActivePackages() {
            return Collections.emptyList();
        }

        @Override
        List<PackageInfo> getFactoryPackages() {
            return Collections.emptyList();
        }

        @Override
        List<PackageInfo> getInactivePackages() {
            return Collections.emptyList();
        }

        @Override
        boolean isApexPackage(String packageName) {
            return false;
        }

        @Override
        @Nullable
        public String getActiveApexPackageNameContainingPackage(
                @NonNull AndroidPackage containedPackage) {
            Objects.requireNonNull(containedPackage);

            return null;
        }

        @Override
        ApexSessionInfo getStagedSessionInfo(int sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        ApexInfoList submitStagedSession(ApexSessionParams params)
                throws PackageManagerException {
            throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                    "Device doesn't support updating APEX");
        }

        @Override
        void markStagedSessionReady(int sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        void markStagedSessionSuccessful(int sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        boolean isApexSupported() {
            return false;
        }

        @Override
        boolean revertActiveSessions() {
            throw new UnsupportedOperationException();
        }

        @Override
        boolean abortStagedSession(int sessionId) throws PackageManagerException {
            throw new UnsupportedOperationException();
        }

        @Override
        boolean uninstallApex(String apexPackagePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        void registerApkInApex(AndroidPackage pkg) {
            // No-op
        }

        @Override
        void reportErrorWithApkInApex(String scanDirPath) {
            // No-op
        }

        @Override
        boolean isApkInApexInstallSuccess(String apexPackageName) {
            return true;
        }

        @Override
        List<String> getApksInApex(String apexPackageName) {
            return Collections.emptyList();
        }

        @Override
        @Nullable
        public String getApexModuleNameForPackageName(String apexPackageName) {
            return null;
        }

        @Override
        public long snapshotCeData(int userId, int rollbackId, String apexPackageName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean restoreCeData(int userId, int rollbackId, String apexPackageName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean destroyDeSnapshots(int rollbackId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean destroyCeSnapshotsNotSpecified(int userId, int[] retainRollbackIds) {
            return true;
        }

        @Override
        void dump(PrintWriter pw, String packageName) {
            // No-op
        }
    }
}
