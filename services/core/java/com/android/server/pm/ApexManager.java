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
 * limitations under the License.s
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
import android.content.pm.PackageParser.PackageParserException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.sysprop.ApexProperties;
import android.util.ArrayMap;
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
class ApexManager {
    static final String TAG = "ApexManager";
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
    /**
     * A map from {@code apexName} to the {@Link PackageInfo} generated from the {@code
     * AndroidManifest.xml}.
     *
     * <p>Note that key of this map is {@code apexName} field which corresponds to the {@code name}
     * field of {@code apex_manifest.json}.
     */
    // TODO(b/132324953): remove.
    @GuardedBy("mLock")
    private ArrayMap<String, PackageInfo> mApexNameToPackageInfoCache;


    ApexManager(Context context) {
        mContext = context;
        if (!isApexSupported()) {
            mApexService = null;
            return;
        }
        try {
            mApexService = IApexService.Stub.asInterface(
                ServiceManager.getServiceOrThrow("apexservice"));
        } catch (ServiceNotFoundException e) {
            throw new IllegalStateException("Required service apexservice not available");
        }
    }

    static final int MATCH_ACTIVE_PACKAGE = 1 << 0;
    static final int MATCH_FACTORY_PACKAGE = 1 << 1;
    @IntDef(
            flag = true,
            prefix = { "MATCH_"},
            value = {MATCH_ACTIVE_PACKAGE, MATCH_FACTORY_PACKAGE})
    @Retention(RetentionPolicy.SOURCE)
    @interface PackageInfoFlags{}

    void systemReady() {
        if (!isApexSupported()) return;
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                onBootCompleted();
                mContext.unregisterReceiver(this);
            }
        }, new IntentFilter(Intent.ACTION_BOOT_COMPLETED));
    }

    private void populateAllPackagesCacheIfNeeded() {
        synchronized (mLock) {
            if (mAllPackagesCache != null) {
                return;
            }
            mApexNameToPackageInfoCache = new ArrayMap<>();
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
                            // TODO(b/132324953): remove.
                            mApexNameToPackageInfoCache.put(ai.moduleName, pkg);
                        }
                        if (ai.isFactory) {
                            if (factoryPackagesSet.contains(pkg.packageName)) {
                                throw new IllegalStateException(
                                        "Two factory packages have the same name: "
                                                + pkg.packageName);
                            }
                            factoryPackagesSet.add(pkg.packageName);
                        }
                    } catch (PackageParserException pe) {
                        throw new IllegalStateException("Unable to parse: " + ai, pe);
                    }
                }
            } catch (RemoteException re) {
                Slog.e(TAG, "Unable to retrieve packages from apexservice: " + re.toString());
                throw new RuntimeException(re);
            }
        }
    }

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
    @Nullable PackageInfo getPackageInfo(String packageName, @PackageInfoFlags int flags) {
        if (!isApexSupported()) return null;
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

    /**
     * Returns a {@link PackageInfo} for an active APEX package keyed by it's {@code apexName}.
     *
     * @deprecated this API will soon be deleted, please don't depend on it.
     */
    // TODO(b/132324953): delete.
    @Deprecated
    @Nullable PackageInfo getPackageInfoForApexName(String apexName) {
        if (!isApexSupported()) return null;
        populateAllPackagesCacheIfNeeded();
        return mApexNameToPackageInfoCache.get(apexName);
    }

    /**
     * Retrieves information about all active APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         active package.
     */
    List<PackageInfo> getActivePackages() {
        if (!isApexSupported()) return Collections.emptyList();
        populateAllPackagesCacheIfNeeded();
        return mAllPackagesCache
                .stream()
                .filter(item -> isActive(item))
                .collect(Collectors.toList());
    }

    /**
     * Retrieves information about all active pre-installed APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         active pre-installed package.
     */
    List<PackageInfo> getFactoryPackages() {
        if (!isApexSupported()) return Collections.emptyList();
        populateAllPackagesCacheIfNeeded();
        return mAllPackagesCache
                .stream()
                .filter(item -> isFactory(item))
                .collect(Collectors.toList());
    }

    /**
     * Retrieves information about all inactive APEX packages.
     *
     * @return a List of PackageInfo object, each one containing information about a different
     *         inactive package.
     */
    List<PackageInfo> getInactivePackages() {
        if (!isApexSupported()) return Collections.emptyList();
        populateAllPackagesCacheIfNeeded();
        return mAllPackagesCache
                .stream()
                .filter(item -> !isActive(item))
                .collect(Collectors.toList());
    }

    /**
     * Checks if {@code packageName} is an apex package.
     *
     * @param packageName package to check.
     * @return {@code true} if {@code packageName} is an apex package.
     */
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

    /**
     * Retrieves information about an apexd staged session i.e. the internal state used by apexd to
     * track the different states of a session.
     *
     * @param sessionId the identifier of the session.
     * @return an ApexSessionInfo object, or null if the session is not known.
     */
    @Nullable ApexSessionInfo getStagedSessionInfo(int sessionId) {
        if (!isApexSupported()) return null;
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
    boolean submitStagedSession(
            int sessionId, @NonNull int[] childSessionIds, @NonNull ApexInfoList apexInfoList) {
        if (!isApexSupported()) return false;
        try {
            mApexService.submitStagedSession(sessionId, childSessionIds, apexInfoList);
            return true;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        } catch (Exception e) {
            Slog.e(TAG, "apexd verification failed", e);
            return false;
        }
    }

    /**
     * Mark a staged session previously submitted using {@code submitStagedSession} as ready to be
     * applied at next reboot.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as ready.
     * @return true upon success, false if the session is unknown.
     */
    boolean markStagedSessionReady(int sessionId) {
        if (!isApexSupported()) return false;
        try {
            mApexService.markStagedSessionReady(sessionId);
            return true;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to mark session " + sessionId + " ready", e);
            return false;
        }
    }

    /**
     * Marks a staged session as successful.
     *
     * <p>Only activated session can be marked as successful.
     *
     * @param sessionId the identifier of the {@link PackageInstallerSession} being marked as
     *                  successful.
     */
    void markStagedSessionSuccessful(int sessionId) {
        if (!isApexSupported()) return;
        try {
            mApexService.markStagedSessionSuccessful(sessionId);
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            throw new RuntimeException(re);
        } catch (Exception e) {
            // It is fine to just log an exception in this case. APEXd will be able to recover in
            // case markStagedSessionSuccessful fails.
            Slog.e(TAG, "Failed to mark session " + sessionId + " as successful", e);
        }
    }

    /**
     * Whether the current device supports the management of APEX packages.
     *
     * @return true if APEX packages can be managed on this device, false otherwise.
     */
    boolean isApexSupported() {
        return ApexProperties.updatable().orElse(false);
    }

    /**
     * Abandons the (only) active session previously submitted.
     *
     * @return {@code true} upon success, {@code false} if any remote exception occurs
     */
    boolean abortActiveSession() {
        if (!isApexSupported()) return false;
        try {
            mApexService.abortActiveSession();
            return true;
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to contact apexservice", re);
            return false;
        }
    }

    /**
     * Uninstalls given {@code apexPackage}.
     *
     * <p>NOTE. Device must be rebooted in order for uninstall to take effect.
     *
     * @param apexPackagePath package to uninstall.
     * @return {@code true} upon successful uninstall, {@code false} otherwise.
     */
    boolean uninstallApex(String apexPackagePath) {
        if (!isApexSupported()) return false;
        try {
            mApexService.unstagePackages(Collections.singletonList(apexPackagePath));
            return true;
        } catch (Exception e) {
            return false;
        }
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

    /**
     * Dump information about the packages contained in a particular cache
     * @param packagesCache the cache to print information about.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set, only
     *                    information about that specific package will be dumped.
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

    /**
     * Dumps various state information to the provided {@link PrintWriter} object.
     *
     * @param pw the {@link PrintWriter} object to send information to.
     * @param packageName a {@link String} containing a package name, or {@code null}. If set, only
     *                    information about that specific package will be dumped.
     */
    void dump(PrintWriter pw, @Nullable String packageName) {
        if (!isApexSupported()) return;
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

    public void onBootCompleted() {
        if (!isApexSupported()) return;
        populateAllPackagesCacheIfNeeded();
    }
}
