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
import android.apex.IApexService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.sysprop.ApexProperties;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.IndentingPrintWriter;

import java.io.File;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ApexManager class handles communications with the apex service to perform operation and queries,
 * as well as providing caching to avoid unnecessary calls to the service.
 */
abstract class ApexManager {

    private static final String TAG = "ApexManager";

    static final int MATCH_ACTIVE_PACKAGE = 1 << 0;
    static final int MATCH_FACTORY_PACKAGE = 1 << 1;

    /**
     * Returns an instance of either {@link ApexManagerImpl} or {@link ApexManagerNoOp} depending
     * on whenever this device supports APEX, i.e. {@link ApexProperties#updatable()} evaluates to
     * {@code true}.
     */
    static ApexManager create(Context systemContext) {
        if (ApexProperties.updatable().orElse(false)) {
            try {
                return new ApexManagerImpl(systemContext, IApexService.Stub.asInterface(
                        ServiceManager.getServiceOrThrow("apexservice")));
            } catch (ServiceManager.ServiceNotFoundException e) {
                throw new IllegalStateException("Required service apexservice not available");
            }
        } else {
            return new ApexManagerNoOp();
        }
    }

    abstract void systemReady();

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
    abstract PackageInfo getPackageInfo(String packageName, @PackageInfoFlags int flags);

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
     * @param sessionId the identifier of the {@link PackageInstallerSession} being submitted.
     * @param childSessionIds if {@code sessionId} is a multi-package session, this should contain
     *                        an array of identifiers of all the child sessions. Otherwise it should
     *                        be an empty array.
     * @param apexInfoList this is an output parameter, which needs to be initialized by tha caller
     *                     and will be filled with a list of {@link ApexInfo} objects, each of which
     *                     contains metadata about one of the packages being submitted as part of
     *                     the session.
     * @return whether the submission of the session was successful.
     */
    abstract boolean submitStagedSession(
            int sessionId, @NonNull int[] childSessionIds, @NonNull ApexInfoList apexInfoList);

    /**
     * Mark a staged session previously submitted using {@code submitStagedSession} as ready to be
     * applied at next reboot.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as ready.
     * @return true upon success, false if the session is unknown.
     */
    abstract boolean markStagedSessionReady(int sessionId);

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
    abstract boolean abortActiveSession();

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
    private static class ApexManagerImpl extends ApexManager {
        private final IApexService mApexService;
        private final Context mContext;
        private final Object mLock = new Object();
        /**
         * A map from {@code APEX packageName} to the {@Link PackageInfo} generated from the {@code
         * AndroidManifest.xml}
         *
         * <p>Note that key of this map is {@code packageName} field of the corresponding {@code
         * AndroidManifest.xml}.
          */
        @GuardedBy("mLock")
        private List<PackageInfo> mAllPackagesCache;

        ApexManagerImpl(Context context, IApexService apexService) {
            mContext = context;
            mApexService = apexService;
        }

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
         * Whether the APEX package is pre-installed or not.
         *
         * @param packageInfo the package to check
         * @return {@code true} if this package is pre-installed, {@code false} otherwise.
         */
        private static boolean isFactory(PackageInfo packageInfo) {
            return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }

        @Override
        void systemReady() {
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    populateAllPackagesCacheIfNeeded();
                    mContext.unregisterReceiver(this);
                }
            }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
        }

        private void populateAllPackagesCacheIfNeeded() {
            synchronized (mLock) {
                if (mAllPackagesCache != null) {
                    return;
                }
                try {
                    mAllPackagesCache = new ArrayList<>();
                    HashSet<String> activePackagesSet = new HashSet<>();
                    HashSet<String> factoryPackagesSet = new HashSet<>();
                    final ApexInfo[] allPkgs = mApexService.getAllPackages();
                    for (ApexInfo ai : allPkgs) {
                        // If the device is using flattened APEX, don't report any APEX
                        // packages since they won't be managed or updated by PackageManager.
                        if ((new File(ai.modulePath)).isDirectory()) {
                            break;
                        }
                        try {
                            final PackageInfo pkg = PackageParser.generatePackageInfoFromApex(
                                    ai, PackageManager.GET_META_DATA
                                            | PackageManager.GET_SIGNING_CERTIFICATES);
                            mAllPackagesCache.add(pkg);
                            if (ai.isActive) {
                                if (activePackagesSet.contains(pkg.packageName)) {
                                    throw new IllegalStateException(
                                            "Two active packages have the same name: "
                                                    + pkg.packageName);
                                }
                                activePackagesSet.add(pkg.packageName);
                            }
                            if (ai.isFactory) {
                                if (factoryPackagesSet.contains(pkg.packageName)) {
                                    throw new IllegalStateException(
                                            "Two factory packages have the same name: "
                                                    + pkg.packageName);
                                }
                                factoryPackagesSet.add(pkg.packageName);
                            }
                        } catch (PackageParser.PackageParserException pe) {
                            throw new IllegalStateException("Unable to parse: " + ai, pe);
                        }
                    }
                } catch (RemoteException re) {
                    Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
                    throw new RuntimeException(re);
                }
            }
        }

        @Override
        @Nullable PackageInfo getPackageInfo(String packageName, @PackageInfoFlags int flags) {
            populateAllPackagesCacheIfNeeded();
            boolean matchActive = (flags & MATCH_ACTIVE_PACKAGE) != 0;
            boolean matchFactory = (flags & MATCH_FACTORY_PACKAGE) != 0;
            for (PackageInfo packageInfo: mAllPackagesCache) {
                if (!packageInfo.packageName.equals(packageName)) {
                    continue;
                }
                if ((!matchActive || isActive(packageInfo))
                        && (!matchFactory || isFactory(packageInfo))) {
                    return packageInfo;
                }
            }
            return null;
        }

        @Override
        List<PackageInfo> getActivePackages() {
            populateAllPackagesCacheIfNeeded();
            return mAllPackagesCache
                    .stream()
                    .filter(item -> isActive(item))
                    .collect(Collectors.toList());
        }

        @Override
        List<PackageInfo> getFactoryPackages() {
            populateAllPackagesCacheIfNeeded();
            return mAllPackagesCache
                    .stream()
                    .filter(item -> isFactory(item))
                    .collect(Collectors.toList());
        }

        @Override
        List<PackageInfo> getInactivePackages() {
            populateAllPackagesCacheIfNeeded();
            return mAllPackagesCache
                    .stream()
                    .filter(item -> !isActive(item))
                    .collect(Collectors.toList());
        }

        @Override
        boolean isApexPackage(String packageName) {
            if (!isApexSupported()) return false;
            populateAllPackagesCacheIfNeeded();
            for (PackageInfo packageInfo : mAllPackagesCache) {
                if (packageInfo.packageName.equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        @Nullable ApexSessionInfo getStagedSessionInfo(int sessionId) {
            try {
                ApexSessionInfo apexSessionInfo = mApexService.getStagedSessionInfo(sessionId);
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
        boolean submitStagedSession(
                int sessionId, @NonNull int[] childSessionIds, @NonNull ApexInfoList apexInfoList) {
            try {
                return mApexService.submitStagedSession(sessionId, childSessionIds, apexInfoList);
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                throw new RuntimeException(re);
            }
        }

        @Override
        boolean markStagedSessionReady(int sessionId) {
            try {
                return mApexService.markStagedSessionReady(sessionId);
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                throw new RuntimeException(re);
            }
        }

        @Override
        void markStagedSessionSuccessful(int sessionId) {
            try {
                mApexService.markStagedSessionSuccessful(sessionId);
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
        boolean abortActiveSession() {
            try {
                mApexService.abortActiveSession();
                return true;
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to contact apexservice", re);
                return false;
            }
        }

        @Override
        boolean uninstallApex(String apexPackagePath) {
            try {
                mApexService.unstagePackages(Collections.singletonList(apexPackagePath));
                return true;
            } catch (Exception e) {
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
            for (PackageInfo pi : packagesCache) {
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
                populateAllPackagesCacheIfNeeded();
                ipw.println();
                ipw.println("Active APEX packages:");
                dumpFromPackagesCache(getActivePackages(), packageName, ipw);
                ipw.println("Inactive APEX packages:");
                dumpFromPackagesCache(getInactivePackages(), packageName, ipw);
                ipw.println("Factory APEX packages:");
                dumpFromPackagesCache(getFactoryPackages(), packageName, ipw);
                ipw.increaseIndent();
                ipw.println("APEX session state:");
                ipw.increaseIndent();
                final ApexSessionInfo[] sessions = mApexService.getSessions();
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
                    } else if (si.isRollbackInProgress) {
                        ipw.println("State: ROLLBACK IN PROGRESS");
                    } else if (si.isRolledBack) {
                        ipw.println("State: ROLLED BACK");
                    } else if (si.isRollbackFailed) {
                        ipw.println("State: ROLLBACK FAILED");
                    }
                    ipw.decreaseIndent();
                }
                ipw.decreaseIndent();
            } catch (RemoteException e) {
                ipw.println("Couldn't communicate with apexd.");
            }
        }
    }

    /**
     * An implementation of {@link ApexManager} that should be used in case device does not support
     * updating APEX packages.
     */
    private static final class ApexManagerNoOp extends ApexManager {

        @Override
        void systemReady() {
            // No-op
        }

        @Override
        PackageInfo getPackageInfo(String packageName, int flags) {
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
        ApexSessionInfo getStagedSessionInfo(int sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        boolean submitStagedSession(int sessionId, int[] childSessionIds,
                ApexInfoList apexInfoList) {
            throw new UnsupportedOperationException();
        }

        @Override
        boolean markStagedSessionReady(int sessionId) {
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
        boolean abortActiveSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        boolean uninstallApex(String apexPackagePath) {
            throw new UnsupportedOperationException();
        }

        @Override
        void dump(PrintWriter pw, String packageName) {
            // No-op
        }
    }
}
