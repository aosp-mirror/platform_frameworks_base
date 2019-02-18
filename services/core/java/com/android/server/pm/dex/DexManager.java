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

import static android.provider.DeviceConfig.DexBoot;

import static com.android.server.pm.InstructionSets.getAppDexInstructionSets;
import static com.android.server.pm.dex.PackageDexUsage.DexUseInfo;
import static com.android.server.pm.dex.PackageDexUsage.PackageUseInfo;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.os.FileUtils;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.provider.DeviceConfig;
import android.util.Log;
import android.util.Slog;
import android.util.jar.StrictJarFile;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.Installer;
import com.android.server.pm.Installer.InstallerException;
import com.android.server.pm.PackageDexOptimizer;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageManagerServiceUtils;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
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

    private static final String PROPERTY_NAME_PM_DEXOPT_PRIV_APPS_OOB = "pm.dexopt.priv-apps-oob";
    private static final String PROPERTY_NAME_PM_DEXOPT_PRIV_APPS_OOB_LIST =
            "pm.dexopt.priv-apps-oob-list";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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

    private final IPackageManager mPackageManager;
    private final PackageDexOptimizer mPackageDexOptimizer;
    private final Object mInstallLock;
    @GuardedBy("mInstallLock")
    private final Installer mInstaller;

    // Possible outcomes of a dex search.
    private static int DEX_SEARCH_NOT_FOUND = 0;  // dex file not found
    private static int DEX_SEARCH_FOUND_PRIMARY = 1;  // dex file is the primary/base apk
    private static int DEX_SEARCH_FOUND_SPLIT = 2;  // dex file is a split apk
    private static int DEX_SEARCH_FOUND_SECONDARY = 3;  // dex file is a secondary dex

    /**
     * We do not record packages that have no secondary dex files or that are not used by other
     * apps. This is an optimization to reduce the amount of data that needs to be written to
     * disk (apps will not usually be shared so this trims quite a bit the number we record).
     *
     * To make this behaviour transparent to the callers which need use information on packages,
     * DexManager will return this DEFAULT instance from
     * {@link DexManager#getPackageUseInfoOrDefault}. It has no data about secondary dex files and
     * is marked as not being used by other apps. This reflects the intended behaviour when we don't
     * find the package in the underlying data file.
     */
    private final static PackageUseInfo DEFAULT_USE_INFO = new PackageUseInfo();

    public DexManager(Context context, IPackageManager pms, PackageDexOptimizer pdo,
            Installer installer, Object installLock) {
        mContext = context;
        mPackageCodeLocationsCache = new HashMap<>();
        mPackageDexUsage = new PackageDexUsage();
        mPackageManager = pms;
        mPackageDexOptimizer = pdo;
        mInstaller = installer;
        mInstallLock = installLock;
        mDynamicCodeLogger = new DynamicCodeLogger(pms, installer);
    }

    public DynamicCodeLogger getDynamicCodeLogger() {
        return mDynamicCodeLogger;
    }

    /**
     * Notify about dex files loads.
     * Note that this method is invoked when apps load dex files and it should
     * return as fast as possible.
     *
     * @param loadingAppInfo the package performing the load
     * @param classLoadersNames the names of the class loaders present in the loading chain. The
     *    list encodes the class loader chain in the natural order. The first class loader has
     *    the second one as its parent and so on. The dex files present in the class path of the
     *    first class loader will be recorded in the usage file.
     * @param classPaths the class paths corresponding to the class loaders names from
     *     {@param classLoadersNames}. The the first element corresponds to the first class loader
     *     and so on. A classpath is represented as a list of dex files separated by
     *     {@code File.pathSeparator}, or null if the class loader's classpath is not known.
     *     The dex files found in the first class path will be recorded in the usage file.
     * @param loaderIsa the ISA of the app loading the dex files
     * @param loaderUserId the user id which runs the code loading the dex files
     */
    public void notifyDexLoad(ApplicationInfo loadingAppInfo, List<String> classLoadersNames,
            List<String> classPaths, String loaderIsa, int loaderUserId) {
        try {
            notifyDexLoadInternal(loadingAppInfo, classLoadersNames, classPaths, loaderIsa,
                    loaderUserId);
        } catch (Exception e) {
            Slog.w(TAG, "Exception while notifying dex load for package " +
                    loadingAppInfo.packageName, e);
        }
    }

    @VisibleForTesting
    /*package*/ void notifyDexLoadInternal(ApplicationInfo loadingAppInfo,
            List<String> classLoaderNames, List<String> classPaths, String loaderIsa,
            int loaderUserId) {
        if (classLoaderNames.size() != classPaths.size()) {
            Slog.wtf(TAG, "Bad call to noitfyDexLoad: args have different size");
            return;
        }
        if (classLoaderNames.isEmpty()) {
            Slog.wtf(TAG, "Bad call to notifyDexLoad: class loaders list is empty");
            return;
        }
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            Slog.w(TAG, "Loading dex files " + classPaths + " in unsupported ISA: " +
                    loaderIsa + "?");
            return;
        }

        // The first classpath should never be null because the first classloader
        // should always be an instance of BaseDexClassLoader.
        String firstClassPath = classPaths.get(0);
        if (firstClassPath == null) {
            return;
        }
        // The classpath is represented as a list of dex files separated by File.pathSeparator.
        String[] dexPathsToRegister = firstClassPath.split(File.pathSeparator);

        // Encode the class loader contexts for the dexPathsToRegister.
        String[] classLoaderContexts = DexoptUtils.processContextForDexLoad(
                classLoaderNames, classPaths);

        // A null classLoaderContexts means that there are unsupported class loaders in the
        // chain.
        if (classLoaderContexts == null) {
            if (DEBUG) {
                Slog.i(TAG, loadingAppInfo.packageName +
                        " uses unsupported class loader in " + classLoaderNames);
            }
        }

        int dexPathIndex = 0;
        for (String dexPath : dexPathsToRegister) {
            // Find the owning package name.
            DexSearchResult searchResult = getDexPackage(loadingAppInfo, dexPath, loaderUserId);

            if (DEBUG) {
                Slog.i(TAG, loadingAppInfo.packageName
                    + " loads from " + searchResult + " : " + loaderUserId + " : " + dexPath);
            }

            if (searchResult.mOutcome != DEX_SEARCH_NOT_FOUND) {
                // TODO(calin): extend isUsedByOtherApps check to detect the cases where
                // different apps share the same runtime. In that case we should not mark the dex
                // file as isUsedByOtherApps. Currently this is a safe approximation.
                boolean isUsedByOtherApps = !loadingAppInfo.packageName.equals(
                        searchResult.mOwningPackageName);
                boolean primaryOrSplit = searchResult.mOutcome == DEX_SEARCH_FOUND_PRIMARY ||
                        searchResult.mOutcome == DEX_SEARCH_FOUND_SPLIT;

                if (primaryOrSplit && !isUsedByOtherApps) {
                    // If the dex file is the primary apk (or a split) and not isUsedByOtherApps
                    // do not record it. This case does not bring any new usable information
                    // and can be safely skipped.
                    continue;
                }

                if (!primaryOrSplit) {
                    // Record loading of a DEX file from an app data directory.
                    mDynamicCodeLogger.recordDex(loaderUserId, dexPath,
                            searchResult.mOwningPackageName, loadingAppInfo.packageName);
                }

                if (classLoaderContexts != null) {

                    // Record dex file usage. If the current usage is a new pattern (e.g. new
                    // secondary, or UsedByOtherApps), record will return true and we trigger an
                    // async write to disk to make sure we don't loose the data in case of a reboot.

                    String classLoaderContext = classLoaderContexts[dexPathIndex];
                    if (mPackageDexUsage.record(searchResult.mOwningPackageName,
                            dexPath, loaderUserId, loaderIsa, isUsedByOtherApps, primaryOrSplit,
                            loadingAppInfo.packageName, classLoaderContext)) {
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
            dexPathIndex++;
        }
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
        } catch (Exception e) {
            mPackageDexUsage.clear();
            mDynamicCodeLogger.clear();
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
            mDynamicCodeLogger.removePackage(packageName);
        } else {
            if (mPackageDexUsage.removeUserPackage(packageName, userId)) {
                mPackageDexUsage.maybeWriteAsync();
            }
            mDynamicCodeLogger.removeUserPackage(packageName, userId);
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
            mPackageDexUsage.syncData(packageToUsersMap, packageToCodePaths);
        } catch (Exception e) {
            mPackageDexUsage.clear();
            Slog.w(TAG, "Exception while loading package dex usage. "
                    + "Starting with a fresh state.", e);
        }

        try {
            mDynamicCodeLogger.readAndSync(packageToUsersMap);
        } catch (Exception e) {
            mDynamicCodeLogger.clear();
            Slog.w(TAG, "Exception while loading package dynamic code usage. "
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
        PackageUseInfo useInfo = mPackageDexUsage.getPackageUseInfo(packageName);
        return useInfo == null ? DEFAULT_USE_INFO : useInfo;
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
    public boolean dexoptSecondaryDex(DexoptOptions options) {
        // Select the dex optimizer based on the force parameter.
        // Forced compilation is done through ForcedUpdatePackageDexOptimizer which will adjust
        // the necessary dexopt flags to make sure that compilation is not skipped. This avoid
        // passing the force flag through the multitude of layers.
        // Note: The force option is rarely used (cmdline input for testing, mostly), so it's OK to
        //       allocate an object here.
        PackageDexOptimizer pdo = options.isForce()
                ? new PackageDexOptimizer.ForcedUpdatePackageDexOptimizer(mPackageDexOptimizer)
                : mPackageDexOptimizer;
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
                pkg = mPackageManager.getPackageInfo(packageName, /*flags*/0,
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
     * Reconcile the information we have about the secondary dex files belonging to
     * {@code packagName} and the actual dex files. For all dex files that were
     * deleted, update the internal records and delete any generated oat files.
     */
    public void reconcileSecondaryDexFiles(String packageName) {
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
                pkg = mPackageManager.getPackageInfo(packageName, /*flags*/0,
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

    // TODO(calin): questionable API in the presence of class loaders context. Needs amends as the
    // compilation happening here will use a pessimistic context.
    public RegisterDexModuleResult registerDexModule(ApplicationInfo info, String dexPath,
            boolean isUsedByOtherApps, int userId) {
        // Find the owning package record.
        DexSearchResult searchResult = getDexPackage(info, dexPath, userId);

        if (searchResult.mOutcome == DEX_SEARCH_NOT_FOUND) {
            return new RegisterDexModuleResult(false, "Package not found");
        }
        if (!info.packageName.equals(searchResult.mOwningPackageName)) {
            return new RegisterDexModuleResult(false, "Dex path does not belong to package");
        }
        if (searchResult.mOutcome == DEX_SEARCH_FOUND_PRIMARY ||
                searchResult.mOutcome == DEX_SEARCH_FOUND_SPLIT) {
            return new RegisterDexModuleResult(false, "Main apks cannot be registered");
        }

        // We found the package. Now record the usage for all declared ISAs.
        boolean update = false;
        for (String isa : getAppDexInstructionSets(info)) {
            boolean newUpdate = mPackageDexUsage.record(searchResult.mOwningPackageName,
                    dexPath, userId, isa, isUsedByOtherApps, /*primaryOrSplit*/ false,
                    searchResult.mOwningPackageName,
                    PackageDexUsage.UNKNOWN_CLASS_LOADER_CONTEXT);
            update |= newUpdate;
        }
        if (update) {
            mPackageDexUsage.maybeWriteAsync();
        }

        DexUseInfo dexUseInfo = mPackageDexUsage.getPackageUseInfo(searchResult.mOwningPackageName)
                .getDexUseInfoMap().get(dexPath);

        // Try to optimize the package according to the install reason.
        DexoptOptions options = new DexoptOptions(info.packageName,
                PackageManagerService.REASON_INSTALL, /*flags*/0);

        int result = mPackageDexOptimizer.dexOptSecondaryDexPath(info, dexPath, dexUseInfo,
                options);

        // If we fail to optimize the package log an error but don't propagate the error
        // back to the app. The app cannot do much about it and the background job
        // will rety again when it executes.
        // TODO(calin): there might be some value to return the error here but it may
        // cause red herrings since that doesn't mean the app cannot use the module.
        if (result != PackageDexOptimizer.DEX_OPT_FAILED) {
            Slog.e(TAG, "Failed to optimize dex module " + dexPath);
        }
        return new RegisterDexModuleResult(true, "Dex module registered successfully");
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
        // Ignore framework code.
        // TODO(calin): is there a better way to detect it?
        if (dexPath.startsWith("/system/framework/")) {
            return new DexSearchResult("framework", DEX_SEARCH_NOT_FOUND);
        }

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

    private static <K,V> V putIfAbsent(Map<K,V> map, K key, V newValue) {
        V existingValue = map.putIfAbsent(key, newValue);
        return existingValue == null ? newValue : existingValue;
    }

    /**
     * Writes the in-memory package dex usage to disk right away.
     */
    public void writePackageDexUsageNow() {
        mPackageDexUsage.writeNow();
        mDynamicCodeLogger.writeNow();
    }

    /**
     * Returns whether the given package is in the list of privilaged apps that should run out of
     * box. This only makes sense if the feature is enabled. Note that when the the OOB list is
     * empty, all priv apps will run in OOB mode.
     */
    public static boolean isPackageSelectedToRunOob(String packageName) {
        return isPackageSelectedToRunOob(Arrays.asList(packageName));
    }

    /**
     * Returns whether any of the given packages are in the list of privilaged apps that should run
     * out of box. This only makes sense if the feature is enabled. Note that when the the OOB list
     * is empty, all priv apps will run in OOB mode.
     */
    public static boolean isPackageSelectedToRunOob(Collection<String> packageNamesInSameProcess) {
        return isPackageSelectedToRunOobInternal(
                SystemProperties.getBoolean(PROPERTY_NAME_PM_DEXOPT_PRIV_APPS_OOB, false),
                SystemProperties.get(PROPERTY_NAME_PM_DEXOPT_PRIV_APPS_OOB_LIST, "ALL"),
                DeviceConfig.getProperty(DexBoot.NAMESPACE, DexBoot.PRIV_APPS_OOB_ENABLED),
                DeviceConfig.getProperty(DexBoot.NAMESPACE, DexBoot.PRIV_APPS_OOB_WHITELIST),
                packageNamesInSameProcess);
    }

    @VisibleForTesting
    /* package */ static boolean isPackageSelectedToRunOobInternal(
            boolean isDefaultEnabled, String defaultWhitelist, String overrideEnabled,
            String overrideWhitelist, Collection<String> packageNamesInSameProcess) {
        // Allow experiment (if exists) to override device configuration.
        boolean enabled = overrideEnabled != null ? overrideEnabled.equals("true")
                : isDefaultEnabled;
        if (!enabled) {
            return false;
        }

        // Similarly, experiment flag can override the whitelist.
        String whitelist = overrideWhitelist != null ? overrideWhitelist : defaultWhitelist;
        if ("ALL".equals(whitelist)) {
            return true;
        }
        for (String oobPkgName : whitelist.split(",")) {
            if (packageNamesInSameProcess.contains(oobPkgName)) {
                return true;
            }
        }
        return false;
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
        private String mOwningPackageName;
        private int mOutcome;

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
