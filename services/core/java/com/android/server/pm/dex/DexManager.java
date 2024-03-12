/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm.dex;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;

import static java.util.function.Function.identity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackagePartitions;
import android.os.BatteryManager;
import android.os.FileUtils;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.Log;
import android.util.Slog;
import android.util.jar.StrictJarFile;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.Installer.LegacyDexoptDisabledException;
import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceUtils;

import dalvik.system.VMRuntime;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;

/**
 * This class keeps track of how dex files are used.
 * Every time it gets a notification about a dex file being loaded it tracks
 * its owning package and records it in PackageDexUsage (package-dex-usage.list).
 *
 * TODO(calin): Extract related dexopt functionality from PackageManagerService
 * into this class.
 */
public class DexManager {
    private static final String TAG = "DexManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // System server cannot load executable code outside system partitions.
    // However it can load verification data - thus we pick the "verify" compiler filter.
    private static final String SYSTEM_SERVER_COMPILER_FILTER = "verify";

    // The suffix we add to the package name when the loading happens in an isolated process.
    // Note that the double dot creates and "invalid" package name which makes it clear that this
    // is an artificially constructed name.
    private static final String ISOLATED_PROCESS_PACKAGE_SUFFIX = "..isolated";

    private final Context mContext;

    // Maps package name to code locations.
    // It caches the code locations for the installed packages. This allows for
    // faster lookups (no locks) when finding what package owns the dex file.
    @GuardedBy("mPackageCodeLocationsCache")
    private final Map<String, PackageCodeLocations> mPackageCodeLocationsCache;

    // PackageDexUsage handles the actual I/O operations. It is responsible to
    // encode and save the dex usage data.
    private final PackageDexUsage mPackageDexUsage;

    // DynamicCodeLogger handles recording of dynamic code loading - which is similar to
    // PackageDexUsage but records a different aspect of the data.
    // (It additionally includes DEX files loaded with unsupported class loaders, and doesn't
    // record class loaders or ISAs.)
    private final DynamicCodeLogger mDynamicCodeLogger;

    private IPackageManager mPackageManager;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;

    private BatteryManager mBatteryManager = null;
    private PowerManager mPowerManager = null;

    // An integer percentage value used to determine when the device is considered to be on low
    // power for compilation purposes.
    private final int mCriticalBatteryLevel;

    // Possible outcomes of a dex search.
    private static final int DEX_SEARCH_NOT_FOUND = 0;  // dex file not found
    private static final int DEX_SEARCH_FOUND_PRIMARY = 1;  // dex file is the primary/base apk
    private static final int DEX_SEARCH_FOUND_SPLIT = 2;  // dex file is a split apk
    private static final int DEX_SEARCH_FOUND_SECONDARY = 3;  // dex file is a secondary dex

    public DexManager(Context context, PackageDexOptimizer pdo, Installer installer,
            Object installLock, DynamicCodeLogger dynamicCodeLogger) {
        this(context, pdo, installer, installLock, dynamicCodeLogger, null);
    }

    @VisibleForTesting
    public DexManager(Context context, PackageDexOptimizer pdo, Installer installer,
            Object installLock, DynamicCodeLogger dynamicCodeLogger,
            @Nullable IPackageManager packageManager) {
        mContext = context;
        mPackageCodeLocationsCache = new HashMap<>();
        mPackageDexUsage = new PackageDexUsage();
        mPackageDexOptimizer = pdo;
        mInstaller = installer;
        mInstallLock = installLock;
        mDynamicCodeLogger = dynamicCodeLogger;
        mPackageManager = packageManager;

        // This is currently checked to handle tests that pass in a null context.
        // TODO(b/174783329): Modify the tests to pass in a mocked Context, PowerManager,
        //      and BatteryManager.
        if (mContext != null) {
            mPowerManager = mContext.getSystemService(PowerManager.class);

            if (mPowerManager == null) {
                Slog.wtf(TAG, "Power Manager is unavailable at time of Dex Manager start");
            }

            mCriticalBatteryLevel = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_criticalBatteryWarningLevel);
        } else {
            // This value will never be used as the Battery Manager null check will fail first.
            mCriticalBatteryLevel = 0;
        }
    }

    @NonNull
    private IPackageManager getPackageManager() {
        if (mPackageManager == null) {
            mPackageManager = IPackageManager.Stub.asInterface(
                    ServiceManager.getService("package"));
        }
        return mPackageManager;
    }

    /**
     * Notify about dex files loads.
     * Note that this method is invoked when apps load dex files and it should
     * return as fast as possible.
     *
     * @param loadingAppInfo the package performing the load
     * @param classLoaderContextMap a map from file paths to dex files that have been loaded to
     *     the class loader context that was used to load them.
     * @param loaderIsa the ISA of the app loading the dex files
     * @param loaderUserId the user id which runs the code loading the dex files
     * @param loaderIsIsolatedProcess whether or not the loading process is isolated.
     */
    public void notifyDexLoad(ApplicationInfo loadingAppInfo,
            Map<String, String> classLoaderContextMap, String loaderIsa, int loaderUserId,
            boolean loaderIsIsolatedProcess) {
        try {
            notifyDexLoadInternal(loadingAppInfo, classLoaderContextMap, loaderIsa,
                    loaderUserId, loaderIsIsolatedProcess);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Exception while notifying dex load for package " +
                    loadingAppInfo.packageName, e);
        }
    }

    @VisibleForTesting
    /*package*/ void notifyDexLoadInternal(ApplicationInfo loadingAppInfo,
            Map<String, String> classLoaderContextMap, String loaderIsa,
            int loaderUserId, boolean loaderIsIsolatedProcess) {
        if (classLoaderContextMap == null) {
            return;
        }
        if (classLoaderContextMap.isEmpty()) {
            Slog.wtf(TAG, "Bad call to notifyDexLoad: class loaders list is empty");
            return;
        }
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            Slog.w(TAG, "Loading dex files " + classLoaderContextMap.keySet()
                    + " in unsupported ISA: " + loaderIsa + "?");
            return;
        }

        // If this load is coming from an isolated process we need to be able to prevent profile
        // based optimizations. This is because isolated processes are sandboxed and can only read
        // world readable files, so they need world readable optimization files. An
        // example of such a package is webview.
        //
        // In order to prevent profile optimization we pretend that the load is coming from a
        // different package, and so we assign a artificial name to the loading package making it
        // clear that it comes from an isolated process. This blends well with the entire
        // usedByOthers logic without needing to special handle isolated process in all dexopt
        // layers.
        String loadingPackageAmendedName = loadingAppInfo.packageName;
        if (loaderIsIsolatedProcess) {
            loadingPackageAmendedName += ISOLATED_PROCESS_PACKAGE_SUFFIX;
        }
        for (Map.Entry<String, String> mapping : classLoaderContextMap.entrySet()) {
            String dexPath = mapping.getKey();
            // Find the owning package name.
            DexSearchResult searchResult = getDexPackage(loadingAppInfo, dexPath, loaderUserId);

            if (DEBUG) {
                Slog.i(TAG, loadingPackageAmendedName
                        + " loads from " + searchResult + " : " + loaderUserId + " : " + dexPath);
            }

            if (searchResult.mOutcome != DEX_SEARCH_NOT_FOUND) {
                // TODO(calin): extend isUsedByOtherApps check to detect the cases where
                // different apps share the same runtime. In that case we should not mark the dex
                // file as isUsedByOtherApps. Currently this is a safe approximation.
                boolean isUsedByOtherApps =
                        !loadingPackageAmendedName.equals(searchResult.mOwningPackageName);
                boolean primaryOrSplit = searchResult.mOutcome == DEX_SEARCH_FOUND_PRIMARY ||
                        searchResult.mOutcome == DEX_SEARCH_FOUND_SPLIT;

                if (primaryOrSplit && !isUsedByOtherApps
                        && !isPlatformPackage(searchResult.mOwningPackageName)) {
                    // If the dex file is the primary apk (or a split) and not isUsedByOtherApps
                    // do not record it. This case does not bring any new usable information
                    // and can be safely skipped.
                    // Note this is just an optimization that makes things easier to read in the
                    // package-dex-use file since we don't need to pollute it with redundant info.
                    // However, we always record system server packages.
                    continue;
                }

                if (!primaryOrSplit) {
                    // Record loading of a DEX file from an app data directory.
                    mDynamicCodeLogger.recordDex(loaderUserId, dexPath,
                            searchResult.mOwningPackageName, loadingAppInfo.packageName);
                }

                String classLoaderContext = mapping.getValue();

                // Overwrite the class loader context for system server (instead of merging it).
                // We expect system server jars to only change contexts in between OTAs and to
                // otherwise be stable.
                // Instead of implementing a complex clear-context logic post OTA, it is much
                // simpler to always override the context for system server. This way, the context
                // will always be up to date and we will avoid merging which could lead to the
                // the context being marked as variable and thus making dexopt non-optimal.
                boolean overwriteCLC = isPlatformPackage(searchResult.mOwningPackageName);

                if (classLoaderContext != null
                        && VMRuntime.isValidClassLoaderContext(classLoaderContext)) {
                    // Record dex file usage. If the current usage is a new pattern (e.g. new
                    // secondary, or UsedByOtherApps), record will return true and we trigger an
                    // async write to disk to make sure we don't loose the data in case of a reboot.
                    if (mPackageDexUsage.record(searchResult.mOwningPackageName,
                            dexPath, loaderUserId, loaderIsa, primaryOrSplit,
                            loadingPackageAmendedName, classLoaderContext, overwriteCLC)) {
                        mPackageDexUsage.maybeWriteAsync();
                    }
                }
            } else {
                // If we can't find the owner of the dex we simply do not track it. The impact is
                // that the dex file will not be considered for offline optimizations.
                if (DEBUG) {
                    Slog.i(TAG, "Could not find owning package for dex file: " + dexPath);
                }
            }
        }
    }

    /**
     * Check if the dexPath belongs to system server.
     * System server can load code from different location, so we cast a wide-net here, and
     * assume that if the paths is on any of the registered system partitions then it can be loaded
     * by system server.
     */
    private boolean isSystemServerDexPathSupportedForOdex(String dexPath) {
        ArrayList<PackagePartitions.SystemPartition> partitions =
                PackagePartitions.getOrderedPartitions(identity());
        // First check the apex partition as it's not part of the SystemPartitions.
        if (dexPath.startsWith("/apex/")) {
            return true;
        }
        for (int i = 0; i < partitions.size(); i++) {
            if (partitions.get(i).containsPath(dexPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read the dex usage from disk and populate the code cache locations.
     * @param existingPackages a map containing information about what packages
     *          are available to what users. Only packages in this list will be
     *          recognized during notifyDexLoad().
     */
    public void load(Map<Integer, List<PackageInfo>> existingPackages) {
        try {
            loadInternal(existingPackages);
        } catch (RuntimeException e) {
            mPackageDexUsage.clear();
            Slog.w(TAG, "Exception while loading. Starting with a fresh state.", e);
        }
    }

    /**
     * Notifies that a new package was installed for {@code userId}.
     * {@code userId} must not be {@code UserHandle.USER_ALL}.
     *
     * @throws IllegalArgumentException if {@code userId} is {@code UserHandle.USER_ALL}.
     */
    public void notifyPackageInstalled(PackageInfo pi, int userId) {
        if (userId == UserHandle.USER_ALL) {
            throw new IllegalArgumentException(
                "notifyPackageInstalled called with USER_ALL");
        }
        cachePackageInfo(pi, userId);
    }

    /**
     * Notifies that package {@code packageName} was updated.
     * This will clear the UsedByOtherApps mark if it exists.
     */
    public void notifyPackageUpdated(String packageName, String baseCodePath,
            String[] splitCodePaths) {
        cachePackageCodeLocation(packageName, baseCodePath, splitCodePaths, null, /*userId*/ -1);
        // In case there was an update, write the package use info to disk async.
        // Note that we do the writing here and not in PackageDexUsage in order to be
        // consistent with other methods in DexManager (e.g. reconcileSecondaryDexFiles performs
        // multiple updates in PackageDexUsage before writing it).
        if (mPackageDexUsage.clearUsedByOtherApps(packageName)) {
            mPackageDexUsage.maybeWriteAsync();
        }
    }

    /**
     * Notifies that the user {@code userId} data for package {@code packageName}
     * was destroyed. This will remove all usage info associated with the package
     * for the given user.
     * {@code userId} is allowed to be {@code UserHandle.USER_ALL} in which case
     * all usage information for the package will be removed.
     */
    public void notifyPackageDataDestroyed(String packageName, int userId) {
        // In case there was an update, write the package use info to disk async.
        // Note that we do the writing here and not in the lower level classes in order to be
        // consistent with other methods in DexManager (e.g. reconcileSecondaryDexFiles performs
        // multiple updates in PackageDexUsage before writing it).
        if (userId == UserHandle.USER_ALL) {
            if (mPackageDexUsage.removePackage(packageName)) {
                mPackageDexUsage.maybeWriteAsync();
            }
        } else {
            if (mPackageDexUsage.removeUserPackage(packageName, userId)) {
                mPackageDexUsage.maybeWriteAsync();
            }
        }
    }

    /**
     * Caches the code location from the given package info.
     */
    private void cachePackageInfo(PackageInfo pi, int userId) {
        ApplicationInfo ai = pi.applicationInfo;
        String[] dataDirs = new String[] {ai.dataDir, ai.deviceProtectedDataDir,
                ai.credentialProtectedDataDir};
        cachePackageCodeLocation(pi.packageName, ai.sourceDir, ai.splitSourceDirs,
                dataDirs, userId);
    }

    private void cachePackageCodeLocation(String packageName, String baseCodePath,
            String[] splitCodePaths, String[] dataDirs, int userId) {
        synchronized (mPackageCodeLocationsCache) {
            PackageCodeLocations pcl = putIfAbsent(mPackageCodeLocationsCache, packageName,
                    new PackageCodeLocations(packageName, baseCodePath, splitCodePaths));
            // TODO(calin): We are forced to extend the scope of this synchronization because
            // the values of the cache (PackageCodeLocations) are updated in place.
            // Make PackageCodeLocations immutable to simplify the synchronization reasoning.
            pcl.updateCodeLocation(baseCodePath, splitCodePaths);
            if (dataDirs != null) {
                for (String dataDir : dataDirs) {
                    // The set of data dirs includes deviceProtectedDataDir and
                    // credentialProtectedDataDir which might be null for shared
                    // libraries. Currently we don't track these but be lenient
                    // and check in case we ever decide to store their usage data.
                    if (dataDir != null) {
                        pcl.mergeAppDataDirs(dataDir, userId);
                    }
                }
            }
        }
    }

    private void loadInternal(Map<Integer, List<PackageInfo>> existingPackages) {
        Map<String, Set<Integer>> packageToUsersMap = new HashMap<>();
        Map<String, Set<String>> packageToCodePaths = new HashMap<>();

        // Cache the code locations for the installed packages. This allows for
        // faster lookups (no locks) when finding what package owns the dex file.
        for (Map.Entry<Integer, List<PackageInfo>> entry : existingPackages.entrySet()) {
            List<PackageInfo> packageInfoList = entry.getValue();
            int userId = entry.getKey();
            for (PackageInfo pi : packageInfoList) {
                // Cache the code locations.
                cachePackageInfo(pi, userId);

                // Cache two maps:
                //   - from package name to the set of user ids who installed the package.
                //   - from package name to the set of code paths.
                // We will use it to sync the data and remove obsolete entries from
                // mPackageDexUsage.
                Set<Integer> users = putIfAbsent(
                        packageToUsersMap, pi.packageName, new HashSet<>());
                users.add(userId);

                Set<String> codePaths = putIfAbsent(
                    packageToCodePaths, pi.packageName, new HashSet<>());
                codePaths.add(pi.applicationInfo.sourceDir);
                if (pi.applicationInfo.splitSourceDirs != null) {
                    Collections.addAll(codePaths, pi.applicationInfo.splitSourceDirs);
                }
            }
        }

        try {
            mPackageDexUsage.read();
            List<String> packagesToKeepDataAbout = new ArrayList<>();
            mPackageDexUsage.syncData(
                    packageToUsersMap, packageToCodePaths, packagesToKeepDataAbout);
        } catch (RuntimeException e) {
            mPackageDexUsage.clear();
            Slog.w(TAG, "Exception while loading package dex usage. "
                    + "Starting with a fresh state.", e);
        }
    }

    /**
     * Get the package dex usage for the given package name.
     * If there is no usage info the method will return a default {@code PackageUseInfo} with
     * no data about secondary dex files and marked as not being used by other apps.
     *
     * Note that no use info means the package was not used or it was used but not by other apps.
     * Also, note that right now we might prune packages which are not used by other apps.
     * TODO(calin): maybe we should not (prune) so we can have an accurate view when we try
     * to access the package use.
     */
    public PackageUseInfo getPackageUseInfoOrDefault(String packageName) {
        // We do not record packages that have no secondary dex files or that are not used by other
        // apps. This is an optimization to reduce the amount of data that needs to be written to
        // disk (apps will not usually be shared so this trims quite a bit the number we record).
        //
        // To make this behaviour transparent to the callers which need use information on packages,
        // DexManager will return this DEFAULT instance from
        // {@link DexManager#getPackageUseInfoOrDefault}. It has no data about secondary dex files
        // and is marked as not being used by other apps. This reflects the intended behaviour when
        // we don't find the package in the underlying data file.
        PackageUseInfo useInfo = mPackageDexUsage.getPackageUseInfo(packageName);
        return useInfo == null ? new PackageUseInfo(packageName) : useInfo;
    }

    /**
     * Return whether or not the manager has usage information on the give package.
     *
     * Note that no use info means the package was not used or it was used but not by other apps.
     * Also, note that right now we might prune packages which are not used by other apps.
     * TODO(calin): maybe we should not (prune) so we can have an accurate view when we try
     * to access the package use.
     */
    @VisibleForTesting
    /*package*/ boolean hasInfoOnPackage(String packageName) {
        return mPackageDexUsage.getPackageUseInfo(packageName) != null;
    }

    /**
     * Perform dexopt on with the given {@code options} on the secondary dex files.
     * @return true if all secondary dex files were processed successfully (compiled or skipped
     *         because they don't need to be compiled)..
     */
    public boolean dexoptSecondaryDex(DexoptOptions options) throws LegacyDexoptDisabledException {
        if (isPlatformPackage(options.getPackageName())) {
            // We could easily redirect to #dexoptSystemServer in this case. But there should be
            // no-one calling this method directly for system server.
            // As such we prefer to abort in this case.
            Slog.wtf(TAG, "System server jars should be optimized with dexoptSystemServer");
            return false;
        }

        PackageDexOptimizer pdo = getPackageDexOptimizer(options);
        String packageName = options.getPackageName();
        PackageUseInfo useInfo = getPackageUseInfoOrDefault(packageName);
        if (useInfo.getDexUseInfoMap().isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No secondary dex use for package:" + packageName);
            }
            // Nothing to compile, return true.
            return true;
        }
        boolean success = true;
        for (Map.Entry<String, DexUseInfo> entry : useInfo.getDexUseInfoMap().entrySet()) {
            String dexPath = entry.getKey();
            DexUseInfo dexUseInfo = entry.getValue();

            PackageInfo pkg;
            try {
                pkg = getPackageManager().getPackageInfo(packageName, /*flags*/0,
                    dexUseInfo.getOwnerUserId());
            } catch (RemoteException e) {
                throw new AssertionError(e);
            }
            // It may be that the package gets uninstalled while we try to compile its
            // secondary dex files. If that's the case, just ignore.
            // Note that we don't break the entire loop because the package might still be
            // installed for other users.
            if (pkg == null) {
                Slog.d(TAG, "Could not find package when compiling secondary dex " + packageName
                        + " for user " + dexUseInfo.getOwnerUserId());
                mPackageDexUsage.removeUserPackage(packageName, dexUseInfo.getOwnerUserId());
                continue;
            }

            int result = pdo.dexOptSecondaryDexPath(pkg.applicationInfo, dexPath,
                    dexUseInfo, options);
            success = success && (result != PackageDexOptimizer.DEX_OPT_FAILED);
        }
        return success;
    }

    /**
     * Select the dex optimizer based on the force parameter.
     * Forced compilation is done through ForcedUpdatePackageDexOptimizer which will adjust
     * the necessary dexopt flags to make sure that compilation is not skipped. This avoid
     * passing the force flag through the multitude of layers.
     * Note: The force option is rarely used (cmdline input for testing, mostly), so it's OK to
     *       allocate an object here.
     */
    private PackageDexOptimizer getPackageDexOptimizer(DexoptOptions options) {
        return options.isForce()
                ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(mPackageDexOptimizer)
                : mPackageDexOptimizer;
    }

    /**
     * Reconcile the information we have about the secondary dex files belonging to
     * {@code packagName} and the actual dex files. For all dex files that were
     * deleted, update the internal records and delete any generated oat files.
     */
    public void reconcileSecondaryDexFiles(String packageName)
            throws LegacyDexoptDisabledException {
        PackageUseInfo useInfo = getPackageUseInfoOrDefault(packageName);
        if (useInfo.getDexUseInfoMap().isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, "No secondary dex use for package:" + packageName);
            }
            // Nothing to reconcile.
            return;
        }

        boolean updated = false;
        for (Map.Entry<String, DexUseInfo> entry : useInfo.getDexUseInfoMap().entrySet()) {
            String dexPath = entry.getKey();
            DexUseInfo dexUseInfo = entry.getValue();
            PackageInfo pkg = null;
            try {
                // Note that we look for the package in the PackageManager just to be able
                // to get back the real app uid and its storage kind. These are only used
                // to perform extra validation in installd.
                // TODO(calin): maybe a bit overkill.
                pkg = getPackageManager().getPackageInfo(packageName, /*flags*/0,
                    dexUseInfo.getOwnerUserId());
            } catch (RemoteException ignore) {
                // Can't happen, DexManager is local.
            }
            if (pkg == null) {
                // It may be that the package was uninstalled while we process the secondary
                // dex files.
                Slog.d(TAG, "Could not find package when compiling secondary dex " + packageName
                        + " for user " + dexUseInfo.getOwnerUserId());
                // Update the usage and continue, another user might still have the package.
                updated = mPackageDexUsage.removeUserPackage(
                        packageName, dexUseInfo.getOwnerUserId()) || updated;
                continue;
            }

            // Special handle system server files.
            // We don't need an installd call because we have permissions to check if the file
            // exists.
            if (isPlatformPackage(packageName)) {
                if (!Files.exists(Paths.get(dexPath))) {
                    if (DEBUG) {
                        Slog.w(TAG, "A dex file previously loaded by System Server does not exist "
                                + " anymore: " + dexPath);
                    }
                    updated = mPackageDexUsage.removeUserPackage(
                            packageName, dexUseInfo.getOwnerUserId()) || updated;
                }
                continue;
            }

            // This is a regular application.
            ApplicationInfo info = pkg.applicationInfo;
            int flags = 0;
            if (info.deviceProtectedDataDir != null &&
                    FileUtils.contains(info.deviceProtectedDataDir, dexPath)) {
                flags |= StorageManager.FLAG_STORAGE_DE;
            } else if (info.credentialProtectedDataDir!= null &&
                    FileUtils.contains(info.credentialProtectedDataDir, dexPath)) {
                flags |= StorageManager.FLAG_STORAGE_CE;
            } else {
                Slog.e(TAG, "Could not infer CE/DE storage for path " + dexPath);
                updated = mPackageDexUsage.removeDexFile(
                        packageName, dexPath, dexUseInfo.getOwnerUserId()) || updated;
                continue;
            }

            boolean dexStillExists = true;
            synchronized(mInstallLock) {
                try {
                    String[] isas = dexUseInfo.getLoaderIsas().toArray(new String[0]);
                    dexStillExists = mInstaller.reconcileSecondaryDexFile(dexPath, packageName,
                            info.uid, isas, info.volumeUuid, flags);
                } catch (InstallerException e) {
                    Slog.e(TAG, "Got InstallerException when reconciling dex " + dexPath +
                            " : " + e.getMessage());
                }
            }
            if (!dexStillExists) {
                updated = mPackageDexUsage.removeDexFile(
                        packageName, dexPath, dexUseInfo.getOwnerUserId()) || updated;
            }

        }
        if (updated) {
            mPackageDexUsage.maybeWriteAsync();
        }
    }

    /**
     * Return all packages that contain records of secondary dex files.
     */
    public Set<String> getAllPackagesWithSecondaryDexFiles() {
        return mPackageDexUsage.getAllPackagesWithSecondaryDexFiles();
    }

    /**
     * Retrieves the package which owns the given dexPath.
     */
    private DexSearchResult getDexPackage(
            ApplicationInfo loadingAppInfo, String dexPath, int userId) {
        // First, check if the package which loads the dex file actually owns it.
        // Most of the time this will be true and we can return early.
        PackageCodeLocations loadingPackageCodeLocations =
                new PackageCodeLocations(loadingAppInfo, userId);
        int outcome = loadingPackageCodeLocations.searchDex(dexPath, userId);
        if (outcome != DEX_SEARCH_NOT_FOUND) {
            // TODO(calin): evaluate if we bother to detect symlinks at the dexPath level.
            return new DexSearchResult(loadingPackageCodeLocations.mPackageName, outcome);
        }

        // The loadingPackage does not own the dex file.
        // Perform a reverse look-up in the cache to detect if any package has ownership.
        // Note that we can have false negatives if the cache falls out of date.
        synchronized (mPackageCodeLocationsCache) {
            for (PackageCodeLocations pcl : mPackageCodeLocationsCache.values()) {
                outcome = pcl.searchDex(dexPath, userId);
                if (outcome != DEX_SEARCH_NOT_FOUND) {
                    return new DexSearchResult(pcl.mPackageName, outcome);
                }
            }
        }

        // We could not find the owning package amongst regular apps.
        // If the loading package is system server, see if the dex file resides
        // on any of the potentially system server owning location and if so,
        // assuming system server ownership.
        //
        // Note: We don't have any way to detect which code paths are actually
        // owned by system server. We can only assume that such paths are on
        // system partitions.
        if (isPlatformPackage(loadingAppInfo.packageName)) {
            if (isSystemServerDexPathSupportedForOdex(dexPath)) {
                // We record system server dex files as secondary dex files.
                // The reason is that we only record the class loader context for secondary dex
                // files and we expect that all primary apks are loaded with an empty class loader.
                // System server dex files may be loaded in non-empty class loader so we need to
                // keep track of their context.
                return new DexSearchResult(PLATFORM_PACKAGE_NAME, DEX_SEARCH_FOUND_SECONDARY);
            } else {
                Slog.wtf(TAG, "System server loads dex files outside paths supported for odex: "
                        + dexPath);
            }
        }

        if (DEBUG) {
            // TODO(calin): Consider checking for /data/data symlink.
            // /data/data/ symlinks /data/user/0/ and there's nothing stopping apps
            // to load dex files through it.
            try {
                String dexPathReal = PackageManagerServiceUtils.realpath(new File(dexPath));
                if (!dexPath.equals(dexPathReal)) {
                    Slog.d(TAG, "Dex loaded with symlink. dexPath=" +
                            dexPath + " dexPathReal=" + dexPathReal);
                }
            } catch (IOException e) {
                // Ignore
            }
        }
        // Cache miss. The cache is updated during installs and uninstalls,
        // so if we get here we're pretty sure the dex path does not exist.
        return new DexSearchResult(null, DEX_SEARCH_NOT_FOUND);
    }

    /** Returns true if this is the platform package .*/
    private static boolean isPlatformPackage(String packageName) {
        return PLATFORM_PACKAGE_NAME.equals(packageName);
    }

    private static <K,V> V putIfAbsent(Map<K,V> map, K key, V newValue) {
        V existingValue = map.putIfAbsent(key, newValue);
        return existingValue == null ? newValue : existingValue;
    }

    /**
     * Writes the in-memory package dex usage to disk right away.
     */
    public void writePackageDexUsageNow() {
        mPackageDexUsage.writeNow();
    }

    /**
     * Generates log if the archive located at {@code fileName} has uncompressed dex file that can
     * be direclty mapped.
     */
    public static boolean auditUncompressedDexInApk(String fileName) {
        StrictJarFile jarFile = null;
        try {
            jarFile = new StrictJarFile(fileName,
                    false /*verify*/, false /*signatureSchemeRollbackProtectionsEnforced*/);
            Iterator<ZipEntry> it = jarFile.iterator();
            boolean allCorrect = true;
            while (it.hasNext()) {
                ZipEntry entry = it.next();
                if (entry.getName().endsWith(".dex")) {
                    if (entry.getMethod() != ZipEntry.STORED) {
                        allCorrect = false;
                        Slog.w(TAG, "APK " + fileName + " has compressed dex code " +
                                entry.getName());
                    } else if ((entry.getDataOffset() & 0x3) != 0) {
                        allCorrect = false;
                        Slog.w(TAG, "APK " + fileName + " has unaligned dex code " +
                                entry.getName());
                    }
                }
            }
            return allCorrect;
        } catch (IOException ignore) {
            Slog.wtf(TAG, "Error when parsing APK " + fileName);
            return false;
        } finally {
            try {
                if (jarFile != null) {
                    jarFile.close();
                }
            } catch (IOException ignore) {}
        }
    }

    /**
     * Translates install scenarios into compilation reasons.  This process can be influenced
     * by the state of the device.
     */
    public int getCompilationReasonForInstallScenario(int installScenario) {
        // Compute the compilation reason from the installation scenario.

        boolean resourcesAreCritical = areBatteryThermalOrMemoryCritical();
        switch (installScenario) {
            case PackageManager.INSTALL_SCENARIO_DEFAULT: {
                return PackageManagerService.REASON_INSTALL;
            }
            case PackageManager.INSTALL_SCENARIO_FAST: {
                return PackageManagerService.REASON_INSTALL_FAST;
            }
            case PackageManager.INSTALL_SCENARIO_BULK: {
                if (resourcesAreCritical) {
                    return PackageManagerService.REASON_INSTALL_BULK_DOWNGRADED;
                } else {
                    return PackageManagerService.REASON_INSTALL_BULK;
                }
            }
            case PackageManager.INSTALL_SCENARIO_BULK_SECONDARY: {
                if (resourcesAreCritical) {
                    return PackageManagerService.REASON_INSTALL_BULK_SECONDARY_DOWNGRADED;
                } else {
                    return PackageManagerService.REASON_INSTALL_BULK_SECONDARY;
                }
            }
            default: {
                throw new IllegalArgumentException("Invalid installation scenario");
            }
        }
    }

    /**
     * Fetches the battery manager object and caches it if it hasn't been fetched already.
     */
    private BatteryManager getBatteryManager() {
        if (mBatteryManager == null && mContext != null) {
            mBatteryManager = mContext.getSystemService(BatteryManager.class);
        }

        return mBatteryManager;
    }

    /**
     * Returns true if the battery level, device temperature, or memory usage are considered to be
     * in a critical state.
     */
    private boolean areBatteryThermalOrMemoryCritical() {
        BatteryManager batteryManager = getBatteryManager();
        boolean isBtmCritical = (batteryManager != null
                && batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                    == BatteryManager.BATTERY_STATUS_DISCHARGING
                && batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    <= mCriticalBatteryLevel)
                || (mPowerManager != null
                    && mPowerManager.getCurrentThermalStatus()
                        >= PowerManager.THERMAL_STATUS_SEVERE);

        return isBtmCritical;
    }

    /**
     * Deletes all the optimizations files generated by ART.
     * This is best effort, and the method will log but not throw errors
     * for individual deletes
     *
     * @param packageInfo the package information.
     * @return the number of freed bytes or -1 if there was an error in the process.
     */
    public long deleteOptimizedFiles(ArtPackageInfo packageInfo)
            throws LegacyDexoptDisabledException {
        long freedBytes = 0;
        boolean hadErrors = false;
        final String packageName = packageInfo.getPackageName();
        for (String codePath : packageInfo.getCodePaths()) {
            for (String isa : packageInfo.getInstructionSets()) {
                try {
                    freedBytes += mInstaller.deleteOdex(packageName, codePath, isa,
                            packageInfo.getOatDir());
                } catch (InstallerException e) {
                    Log.e(TAG, "Failed deleting oat files for " + codePath, e);
                    hadErrors = true;
                }
            }
        }
        return hadErrors ? -1 : freedBytes;
    }

    public static class RegisterDexModuleResult {
        public RegisterDexModuleResult() {
            this(false, null);
        }

        public RegisterDexModuleResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public final boolean success;
        public final String message;
    }

    /**
     * Convenience class to store the different locations where a package might
     * own code.
     */
    private static class PackageCodeLocations {
        private final String mPackageName;
        private String mBaseCodePath;
        private final Set<String> mSplitCodePaths;
        // Maps user id to the application private directory.
        private final Map<Integer, Set<String>> mAppDataDirs;

        public PackageCodeLocations(ApplicationInfo ai, int userId) {
            this(ai.packageName, ai.sourceDir, ai.splitSourceDirs);
            mergeAppDataDirs(ai.dataDir, userId);
        }
        public PackageCodeLocations(String packageName, String baseCodePath,
                String[] splitCodePaths) {
            mPackageName = packageName;
            mSplitCodePaths = new HashSet<>();
            mAppDataDirs = new HashMap<>();
            updateCodeLocation(baseCodePath, splitCodePaths);
        }

        public void updateCodeLocation(String baseCodePath, String[] splitCodePaths) {
            mBaseCodePath = baseCodePath;
            mSplitCodePaths.clear();
            if (splitCodePaths != null) {
                for (String split : splitCodePaths) {
                    mSplitCodePaths.add(split);
                }
            }
        }

        public void mergeAppDataDirs(String dataDir, int userId) {
            Set<String> dataDirs = putIfAbsent(mAppDataDirs, userId, new HashSet<>());
            dataDirs.add(dataDir);
        }

        public int searchDex(String dexPath, int userId) {
            // First check that this package is installed or active for the given user.
            // A missing data dir means the package is not installed.
            Set<String> userDataDirs = mAppDataDirs.get(userId);
            if (userDataDirs == null) {
                return DEX_SEARCH_NOT_FOUND;
            }

            if (mBaseCodePath.equals(dexPath)) {
                return DEX_SEARCH_FOUND_PRIMARY;
            }
            if (mSplitCodePaths.contains(dexPath)) {
                return DEX_SEARCH_FOUND_SPLIT;
            }
            for (String dataDir : userDataDirs) {
                if (dexPath.startsWith(dataDir)) {
                    return DEX_SEARCH_FOUND_SECONDARY;
                }
            }

            return DEX_SEARCH_NOT_FOUND;
        }
    }

    /**
     * Convenience class to store ownership search results.
     */
    private class DexSearchResult {
        private final String mOwningPackageName;
        private final int mOutcome;

        public DexSearchResult(String owningPackageName, int outcome) {
            this.mOwningPackageName = owningPackageName;
            this.mOutcome = outcome;
        }

        @Override
        public String toString() {
            return mOwningPackageName + "-" + mOutcome;
        }
    }
}
