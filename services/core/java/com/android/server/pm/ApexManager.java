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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.apex.ApexInfo;
import android.apex.ApexInfoList;
import android.apex.ApexSessionInfo;
import android.apex.ApexSessionParams;
import android.apex.CompressedApexInfoList;
import android.apex.IApexService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Binder;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Singleton;
import android.util.Slog;
import android.util.SparseArray;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.modules.utils.build.UnboundedSdkLevel;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.component.ParsedApexSystemService;
import com.android.server.utils.TimingsTraceAndSlog;

import com.google.android.collect.Lists;

import java.io.File;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
                    return new ApexManagerImpl();
                }
            };

    /**
     * Returns an instance of {@link ApexManagerImpl}
     * @hide
     */
    public static ApexManager getInstance() {
        return sApexManagerSingleton.get();
    }

    static class ScanResult {
        public final ApexInfo apexInfo;
        public final AndroidPackage pkg;
        public final String packageName;
        ScanResult(ApexInfo apexInfo, AndroidPackage pkg, String packageName) {
            this.apexInfo = apexInfo;
            this.pkg = pkg;
            this.packageName = packageName;
        }
    }

    /**
     * Minimal information about APEX mount points and the original APEX package they refer to.
     * @hide
     */
    public static class ActiveApexInfo {
        @Nullable public final String apexModuleName;
        public final File apexDirectory;
        public final File preInstalledApexPath;
        public final boolean isFactory;
        public final File apexFile;
        public final boolean activeApexChanged;

        private ActiveApexInfo(File apexDirectory, File preInstalledApexPath, File apexFile) {
            this(null, apexDirectory, preInstalledApexPath, true, apexFile, false);
        }

        private ActiveApexInfo(@Nullable String apexModuleName, File apexDirectory,
                File preInstalledApexPath, boolean isFactory, File apexFile,
                boolean activeApexChanged) {
            this.apexModuleName = apexModuleName;
            this.apexDirectory = apexDirectory;
            this.preInstalledApexPath = preInstalledApexPath;
            this.isFactory = isFactory;
            this.apexFile = apexFile;
            this.activeApexChanged = activeApexChanged;
        }

        public ActiveApexInfo(ApexInfo apexInfo) {
            this(
                    apexInfo.moduleName,
                    new File(Environment.getApexDirectory() + File.separator
                            + apexInfo.moduleName),
                    new File(apexInfo.preinstalledModulePath),
                    apexInfo.isFactory,
                    new File(apexInfo.modulePath),
                    apexInfo.activeApexChanged);
        }
    }

    abstract ApexInfo[] getAllApexInfos();
    abstract void notifyScanResult(List<ScanResult> scanResults);

    /**
     * Returns {@link ActiveApexInfo} records relative to all active APEX packages.
     *
     * @hide
     */
    public abstract List<ActiveApexInfo> getActiveApexInfos();

    /**
     * Returns the active apex package's name that contains the (apk) package.
     *
     * @param containedPackageName The (apk) package that might be in a apex
     * @return the apex package's name of {@code null} if the {@code containedPackage} is not inside
     *         any apex.
     */
    @Nullable
    public abstract String getActiveApexPackageNameContainingPackage(
            @NonNull String containedPackageName);

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
     * Returns array of all staged sessions known to apexd.
     */
    @NonNull
    abstract SparseArray<ApexSessionInfo> getSessions();

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
     * Returns {@code ApeInfo} about apex sessions that have been marked ready via
     * {@link #markStagedSessionReady(int)}
     *
     * Returns empty array if there is no staged apex session or if there is any error.
     */
    abstract ApexInfo[] getStagedApexInfos(ApexSessionParams params);

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
     * Abandons the staged session with the given sessionId. Client should handle {@code false}
     * return value carefully as failure here can leave device in inconsistent state.
     *
     * @return {@code true} upon success, {@code false} if any exception occurs
     */
    abstract boolean abortStagedSession(int sessionId);

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
     * @param scanDirPath the directory of the apex inside which apk-in-apex resides.
     * @param errorMsg the actual error that occurred when scanning the path
     */
    abstract void reportErrorWithApkInApex(String scanDirPath, String errorMsg);

    /**
     * Returns null if there were no errors when installing apk-in-apex inside
     * {@param apexPackageName}, otherwise returns the error as string
     *
     * @param apexPackageName Package name of the apk container of apex
     */
    @Nullable
    abstract String getApkInApexInstallError(String apexPackageName);

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
     * Returns the package name of the active APEX whose name is {@code apexModuleName}. If not
     * found, returns {@code null}.
     */
    @Nullable
    public abstract String getActivePackageNameForApexModuleName(String apexModuleName);

    /**
     * Copies the CE apex data directory for the given {@code userId} to a backup location, for use
     * in case of rollback.
     *
     * @return boolean true if the snapshot was successful
     */
    public abstract boolean snapshotCeData(int userId, int rollbackId, String apexPackageName);

    /**
     * Restores the snapshot of the CE apex data directory for the given {@code userId}.
     * Note the snapshot will be deleted after restoration succeeded.
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
     *  Deletes snapshots of the credential encrypted apex data directories for the specified user,
     *  for the given rollback id as long as the user is credential unlocked.
     *
     * @return boolean true if the delete was successful
     */
    public abstract boolean destroyCeSnapshots(int userId, int rollbackId);

    /**
     * Deletes snapshots of the credential encrypted apex data directories for the specified user,
     * where the rollback id is not included in {@code retainRollbackIds} as long as the user is
     * credential unlocked.
     *
     * @return boolean true if the delete was successful
     */
    public abstract boolean destroyCeSnapshotsNotSpecified(int userId, int[] retainRollbackIds);

    /**
     * Inform apexd that the boot has completed.
     */
    public abstract void markBootCompleted();

    /**
     * Estimate how much storage space is needed on /data/ for decompressing apexes
     * @param infoList List of apexes that are compressed in target build.
     * @return Size, in bytes, the amount of space needed on /data/
     */
    public abstract long calculateSizeForCompressedApex(CompressedApexInfoList infoList)
            throws RemoteException;

    /**
     * Reserve space on /data so that apexes can be decompressed after OTA
     * @param infoList List of apexes that are compressed in target build.
     */
    public abstract void reserveSpaceForCompressedApex(CompressedApexInfoList infoList)
            throws RemoteException;

    /**
     * Performs a non-staged install of the given {@code apexFile}.
     *
     * If {@code force} is {@code true}, then  update is forced even for APEXes that do not support
     * non-staged update. This feature is only available on debuggable builds to improve development
     * velocity of the teams that have their code packaged in an APEX.
     *
     * @return {@code ApeInfo} about the newly installed APEX package.
     */
    abstract ApexInfo installPackage(File apexFile, boolean force) throws PackageManagerException;

    /**
     * Get a list of apex system services implemented in an apex.
     *
     * <p>The list is sorted by initOrder for consistency.
     */
    public abstract List<ApexSystemServiceInfo> getApexSystemServices();

    /**
     * Returns an APEX file backing the mount point {@code file} is located on, or {@code null} if
     * {@code file} doesn't belong to a {@code /apex} mount point.
     *
     * <p>Also returns {@code null} if device doesn't support updatable APEX packages.
     */
    @Nullable
    public abstract File getBackingApexFile(@NonNull File file);

    /**
     * Dumps various state information to the provided {@link PrintWriter} object.
     *
     * @param pw the {@link PrintWriter} object to send information to.
     */
    abstract void dump(PrintWriter pw);

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

        // TODO(ioffe): this should be either List or ArrayMap.
        @GuardedBy("mLock")
        private Set<ActiveApexInfo> mActiveApexInfosCache;

        /**
         * Map of all apex system services to the jar files they are contained in.
         */
        @GuardedBy("mLock")
        private final List<ApexSystemServiceInfo> mApexSystemServices = new ArrayList<>();

        /**
         * Contains the list of {@code packageName}s of apks-in-apex for given
         * {@code apexModuleName}. See {@link #mPackageNameToApexModuleName} to understand the
         * difference between {@code packageName} and {@code apexModuleName}.
         */
        @GuardedBy("mLock")
        private final ArrayMap<String, List<String>> mApksInApex = new ArrayMap<>();

        /**
         * Contains the list of {@code Exception}s that were raised when installing apk-in-apex
         * inside {@code apexModuleName}.
         */
        @GuardedBy("mLock")
        private final Map<String, String> mErrorWithApkInApex = new ArrayMap<>();

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
         * Reverse mapping of {@link #mPackageNameToApexModuleName}, for active packages only.
         */
        @GuardedBy("mLock")
        private ArrayMap<String, String> mApexModuleNameToActivePackageName;

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
        ApexInfo[] getAllApexInfos() {
            try {
                return waitForApexService().getAllPackages();
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
                throw new RuntimeException(re);
            }
        }

        @Override
        void notifyScanResult(List<ScanResult> scanResults) {
            synchronized (mLock) {
                notifyScanResultLocked(scanResults);
            }
        }

        @GuardedBy("mLock")
        private void notifyScanResultLocked(List<ScanResult> scanResults) {
            mPackageNameToApexModuleName = new ArrayMap<>();
            mApexModuleNameToActivePackageName = new ArrayMap<>();
            for (ScanResult scanResult : scanResults) {
                ApexInfo ai = scanResult.apexInfo;
                String packageName = scanResult.packageName;
                for (ParsedApexSystemService service :
                        scanResult.pkg.getApexSystemServices()) {
                    String minSdkVersion = service.getMinSdkVersion();
                    if (minSdkVersion != null && !UnboundedSdkLevel.isAtLeast(minSdkVersion)) {
                        Slog.d(TAG, String.format(
                                "ApexSystemService %s with min_sdk_version=%s is skipped",
                                service.getName(), service.getMinSdkVersion()));
                        continue;
                    }
                    String maxSdkVersion = service.getMaxSdkVersion();
                    if (maxSdkVersion != null && !UnboundedSdkLevel.isAtMost(maxSdkVersion)) {
                        Slog.d(TAG, String.format(
                                "ApexSystemService %s with max_sdk_version=%s is skipped",
                                service.getName(), service.getMaxSdkVersion()));
                        continue;
                    }

                    if (ai.isActive) {
                        String name = service.getName();
                        for (int j = 0; j < mApexSystemServices.size(); j++) {
                            ApexSystemServiceInfo info = mApexSystemServices.get(j);
                            if (info.getName().equals(name)) {
                                throw new IllegalStateException(TextUtils.formatSimple(
                                        "Duplicate apex-system-service %s from %s, %s", name,
                                        info.mJarPath, service.getJarPath()));
                            }
                        }
                        ApexSystemServiceInfo info = new ApexSystemServiceInfo(
                                service.getName(), service.getJarPath(),
                                service.getInitOrder());
                        mApexSystemServices.add(info);
                    }
                }
                Collections.sort(mApexSystemServices);
                mPackageNameToApexModuleName.put(packageName, ai.moduleName);
                if (ai.isActive) {
                    if (mApexModuleNameToActivePackageName.containsKey(ai.moduleName)) {
                        throw new IllegalStateException(
                                "Two active packages have the same APEX module name: "
                                        + ai.moduleName);
                    }
                    mApexModuleNameToActivePackageName.put(
                            ai.moduleName, packageName);
                }
            }
        }

        @Override
        public List<ActiveApexInfo> getActiveApexInfos() {
            final TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG + "Timing",
                    Trace.TRACE_TAG_PACKAGE_MANAGER);
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
        @Nullable
        public String getActiveApexPackageNameContainingPackage(String containedPackageName) {
            Objects.requireNonNull(containedPackageName);
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                int numApksInApex = mApksInApex.size();
                for (int apkInApexNum = 0; apkInApexNum < numApksInApex; apkInApexNum++) {
                    if (mApksInApex.valueAt(apkInApexNum).contains(containedPackageName)) {
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
        SparseArray<ApexSessionInfo> getSessions() {
            try {
                final ApexSessionInfo[] sessions = waitForApexService().getSessions();
                final SparseArray<ApexSessionInfo> result = new SparseArray<>(sessions.length);
                for (int i = 0; i < sessions.length; i++) {
                    result.put(sessions[i].sessionId, sessions[i]);
                }
                return result;
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
                        PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
                        "apexd verification failed : " + e.getMessage());
            }
        }

        @Override
        ApexInfo[] getStagedApexInfos(ApexSessionParams params) {
            try {
                return waitForApexService().getStagedApexInfos(params);
            } catch (RemoteException re) {
                Slog.w(TAG, "Unable to contact apexservice" + re.getMessage());
                throw new RuntimeException(re);
            } catch (Exception e) {
                Slog.w(TAG, "Failed to collect staged apex infos" + e.getMessage());
                return new ApexInfo[0];
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
                        PackageManager.INSTALL_FAILED_VERIFICATION_FAILURE,
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
        boolean abortStagedSession(int sessionId) {
            try {
                waitForApexService().abortStagedSession(sessionId);
                return true;
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return false;
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
                    if (pkg.getBaseApkPath().startsWith(
                            aai.apexDirectory.getAbsolutePath() + File.separator)) {
                        List<String> apks = mApksInApex.get(aai.apexModuleName);
                        if (apks == null) {
                            apks = Lists.newArrayList();
                            mApksInApex.put(aai.apexModuleName, apks);
                        }
                        Slog.i(TAG, "Registering " + pkg.getPackageName() + " as apk-in-apex of "
                                + aai.apexModuleName);
                        apks.add(pkg.getPackageName());
                    }
                }
            }
        }

        @Override
        void reportErrorWithApkInApex(String scanDirPath, String errorMsg) {
            synchronized (mLock) {
                for (ActiveApexInfo aai : mActiveApexInfosCache) {
                    if (scanDirPath.startsWith(aai.apexDirectory.getAbsolutePath())) {
                        mErrorWithApkInApex.put(aai.apexModuleName, errorMsg);
                    }
                }
            }
        }

        @Override
        @Nullable
        String getApkInApexInstallError(String apexPackageName) {
            synchronized (mLock) {
                Preconditions.checkState(mPackageNameToApexModuleName != null,
                        "APEX packages have not been scanned");
                String moduleName = mPackageNameToApexModuleName.get(apexPackageName);
                if (moduleName == null) {
                    return null;
                }
                return mErrorWithApkInApex.get(moduleName);
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
        @Nullable
        public String getActivePackageNameForApexModuleName(String apexModuleName) {
            synchronized (mLock) {
                Preconditions.checkState(mApexModuleNameToActivePackageName != null,
                        "APEX packages have not been scanned");
                return mApexModuleNameToActivePackageName.get(apexModuleName);
            }
        }

        @Override
        public boolean snapshotCeData(int userId, int rollbackId, String apexPackageName) {
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
                waitForApexService().snapshotCeData(userId, rollbackId, apexModuleName);
                return true;
            } catch (Exception e) {
                Slog.e(TAG, e.getMessage(), e);
                return false;
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
        public boolean destroyCeSnapshots(int userId, int rollbackId) {
            try {
                waitForApexService().destroyCeSnapshots(userId, rollbackId);
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

        @Override
        public void markBootCompleted() {
            try {
                waitForApexService().markBootCompleted();
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
            }
        }

        @Override
        public long calculateSizeForCompressedApex(CompressedApexInfoList infoList)
                throws RemoteException {
            return waitForApexService().calculateSizeForCompressedApex(infoList);
        }

        @Override
        public void reserveSpaceForCompressedApex(CompressedApexInfoList infoList)
                throws RemoteException {
            waitForApexService().reserveSpaceForCompressedApex(infoList);
        }

        private SigningDetails getSigningDetails(PackageInfo pkg) throws PackageManagerException {
            final int minSignatureScheme =
                    ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                            pkg.applicationInfo.targetSdkVersion);
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<SigningDetails> result = ApkSignatureVerifier.verify(
                    input, pkg.applicationInfo.sourceDir, minSignatureScheme);
            if (result.isError()) {
                throw new PackageManagerException(result.getErrorCode(), result.getErrorMessage(),
                        result.getException());
            }
            return result.getResult();
        }

        private void checkApexSignature(PackageInfo existingApexPkg, PackageInfo newApexPkg)
                throws PackageManagerException {
            final SigningDetails existingSigningDetails = getSigningDetails(existingApexPkg);
            final SigningDetails newSigningDetails = getSigningDetails(newApexPkg);
            if (!newSigningDetails.checkCapability(existingSigningDetails,
                      SigningDetails.CertCapabilities.INSTALLED_DATA)) {
                throw new PackageManagerException(PackageManager.INSTALL_FAILED_BAD_SIGNATURE,
                          "APK container signature of " + newApexPkg.applicationInfo.sourceDir
                                   + " is not compatible with currently installed on device");
            }
        }

        @Override
        ApexInfo installPackage(File apexFile, boolean force)
                throws PackageManagerException {
            try {
                return waitForApexService().installAndActivatePackage(apexFile.getAbsolutePath(),
                        force);
            } catch (RemoteException e) {
                throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                        "apexservice not available");
            } catch (Exception e) {
                // TODO(b/187864524): is INSTALL_FAILED_INTERNAL_ERROR is the right error code here?
                throw new PackageManagerException(PackageManager.INSTALL_FAILED_INTERNAL_ERROR,
                        e.getMessage());
            }
        }

        @Override
        public List<ApexSystemServiceInfo> getApexSystemServices() {
            synchronized (mLock) {
                Preconditions.checkState(mApexSystemServices != null,
                        "APEX packages have not been scanned");
                return mApexSystemServices;
            }
        }

        @Override
        public File getBackingApexFile(File file) {
            Path path = file.toPath();
            if (!path.startsWith(Environment.getApexDirectory().toPath())) {
                return null;
            }
            if (path.getNameCount() < 2) {
                return null;
            }
            String moduleName = file.toPath().getName(1).toString();
            final List<ActiveApexInfo> apexes = getActiveApexInfos();
            for (int i = 0; i < apexes.size(); i++) {
                if (apexes.get(i).apexModuleName.equals(moduleName)) {
                    return apexes.get(i).apexFile;
                }
            }
            return null;
        }

        @Override
        void dump(PrintWriter pw) {
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
            } catch (RemoteException e) {
                ipw.println("Couldn't communicate with apexd.");
            }
        }
    }
}
