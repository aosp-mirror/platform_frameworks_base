/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.pm.PackageManager.INSTALL_FAILED_MISSING_SHARED_LIBRARY;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;
import static com.android.server.pm.PackageManagerService.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.service.pm.PackageServiceDumpProto;
import android.util.ArraySet;
import android.util.PackageUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;
import com.android.server.compat.PlatformCompat;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.parsing.pkg.ParsedPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedLongSparseArray;
import com.android.server.utils.Watcher;

import libcore.util.HexEncoding;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Current known shared libraries on the device.
 */
public final class SharedLibrariesImpl implements SharedLibrariesRead, Watchable, Snappable {
    private static final boolean DEBUG_SHARED_LIBRARIES = false;

    /**
     * Apps targeting Android S and above need to declare dependencies to the public native
     * shared libraries that are defined by the device maker using {@code uses-native-library} tag
     * in its {@code AndroidManifest.xml}.
     *
     * If any of the dependencies cannot be satisfied, i.e. one of the dependency doesn't exist,
     * the package manager rejects to install the app. The dependency can be specified as optional
     * using {@code android:required} attribute in the tag, in which case failing to satisfy the
     * dependency doesn't stop the installation.
     * <p>Once installed, an app is provided with only the native shared libraries that are
     * specified in the app manifest. {@code dlopen}ing a native shared library that doesn't appear
     * in the app manifest will fail even if it actually exists on the device.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long ENFORCE_NATIVE_SHARED_LIBRARY_DEPENDENCIES = 142191088;

    // TODO(b/200588896): remove PMS dependency
    private final PackageManagerService mPm;
    private final PackageManagerServiceInjector mInjector;
    private DeletePackageHelper mDeletePackageHelper; // late init

    // A map of library name to a list of {@link SharedLibraryInfo}s with different versions.
    @Watched
    private final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>
            mSharedLibraries;
    private final SnapshotCache<WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>>
            mSharedLibrariesSnapshot;

    // A map of declaring package name to a list of {@link SharedLibraryInfo}s with different
    // versions.
    @Watched
    private final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>
            mStaticLibsByDeclaringPackage;
    private final SnapshotCache<WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>>
            mStaticLibsByDeclaringPackageSnapshot;

    /**
     * Watchable machinery
     */
    private final WatchableImpl mWatchable = new WatchableImpl();

    /**
     * The observer that watches for changes from array members
     */
    private final Watcher mObserver = new Watcher() {
        @Override
        public void onChange(@Nullable Watchable what) {
            SharedLibrariesImpl.this.dispatchChange(what);
        }
    };

    private final SnapshotCache<SharedLibrariesImpl> mSnapshot;

    // Create a snapshot cache
    private SnapshotCache<SharedLibrariesImpl> makeCache() {
        return new SnapshotCache<SharedLibrariesImpl>(this /* source */, this /* watchable */) {
            @Override
            public SharedLibrariesImpl createSnapshot() {
                final SharedLibrariesImpl sharedLibrariesImpl = new SharedLibrariesImpl(mSource);
                sharedLibrariesImpl.mWatchable.seal();
                return sharedLibrariesImpl;
            }};
    }

    /**
     * Default constructor used in PackageManagerService.
     */
    SharedLibrariesImpl(PackageManagerService pm, PackageManagerServiceInjector injector) {
        mPm = pm;
        mInjector = injector;

        mSharedLibraries = new WatchedArrayMap<>();
        mSharedLibrariesSnapshot = new SnapshotCache.Auto<>(mSharedLibraries, mSharedLibraries,
                "SharedLibrariesImpl.mSharedLibraries");
        mStaticLibsByDeclaringPackage = new WatchedArrayMap<>();
        mStaticLibsByDeclaringPackageSnapshot = new SnapshotCache.Auto<>(
                mStaticLibsByDeclaringPackage, mStaticLibsByDeclaringPackage,
                "SharedLibrariesImpl.mStaticLibsByDeclaringPackage");

        registerObservers();
        Watchable.verifyWatchedAttributes(this, mObserver);
        mSnapshot = makeCache();
    }

    /**
     * Invoked by PMS constructor after the instance of {@link DeletePackageHelper} is ready.
     */
    void setDeletePackageHelper(DeletePackageHelper deletePackageHelper) {
        mDeletePackageHelper = deletePackageHelper;
    }

    private void registerObservers() {
        mSharedLibraries.registerObserver(mObserver);
        mStaticLibsByDeclaringPackage.registerObserver(mObserver);
    }

    /**
     * A copy constructor used in snapshot().
     */
    private SharedLibrariesImpl(SharedLibrariesImpl source) {
        mPm = source.mPm;
        mInjector = source.mInjector;

        mSharedLibraries = source.mSharedLibrariesSnapshot.snapshot();
        mSharedLibrariesSnapshot = new SnapshotCache.Sealed<>();
        mStaticLibsByDeclaringPackage = source.mStaticLibsByDeclaringPackageSnapshot.snapshot();
        mStaticLibsByDeclaringPackageSnapshot = new SnapshotCache.Sealed<>();

        // Do not register any Watchables and do not create a snapshot cache.
        mSnapshot = new SnapshotCache.Sealed();
    }

    /**
     * Ensures an observer is in the list, exactly once. The observer cannot be null.  The
     * function quietly returns if the observer is already in the list.
     *
     * @param observer The {@link Watcher} to be notified when the {@link Watchable} changes.
     */
    @Override
    public void registerObserver(@NonNull Watcher observer) {
        mWatchable.registerObserver(observer);
    }

    /**
     * Ensures an observer is not in the list. The observer must not be null.  The function
     * quietly returns if the objserver is not in the list.
     *
     * @param observer The {@link Watcher} that should not be in the notification list.
     */
    @Override
    public void unregisterObserver(@NonNull Watcher observer) {
        mWatchable.unregisterObserver(observer);
    }

    /**
     * Return true if the {@link Watcher} is a registered observer.
     * @param observer A {@link Watcher} that might be registered
     * @return true if the observer is registered with this {@link Watchable}.
     */
    @Override
    public boolean isRegisteredObserver(@NonNull Watcher observer) {
        return mWatchable.isRegisteredObserver(observer);
    }

    /**
     * Invokes {@link Watcher#onChange} on each registered observer.  The method can be called
     * with the {@link Watchable} that generated the event.  In a tree of {@link Watchable}s, this
     * is generally the first (deepest) {@link Watchable} to detect a change.
     *
     * @param what The {@link Watchable} that generated the event.
     */
    @Override
    public void dispatchChange(@Nullable Watchable what) {
        mWatchable.dispatchChange(what);
    }

    /**
     * Create an immutable copy of the object, suitable for read-only methods.  A snapshot
     * is free to omit state that is only needed for mutating methods.
     */
    @Override
    public @NonNull SharedLibrariesRead snapshot() {
        return mSnapshot.snapshot();
    }

    /**
     * Returns all shared libraries on the device.
     */
    @GuardedBy("mPm.mLock")
    @Override
    public @NonNull WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> getAll() {
        return mSharedLibraries;
    }

    /**
     * Given the library name, returns a list of shared libraries on all versions.
     * TODO: Remove, this is used for live mutation outside of the defined commit path
     */
    @GuardedBy("mPm.mLock")
    @Override
    public @NonNull WatchedLongSparseArray<SharedLibraryInfo> getSharedLibraryInfos(
            @NonNull String libName) {
        return mSharedLibraries.get(libName);
    }

    @VisibleForTesting
    public WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>> getSharedLibraries() {
        return mSharedLibraries;
    }

    /**
     * Returns the shared library with given library name and version number.
     */
    @GuardedBy("mPm.mLock")
    @Override
    public @Nullable SharedLibraryInfo getSharedLibraryInfo(@NonNull String libName, long version) {
        final WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                mSharedLibraries.get(libName);
        if (versionedLib == null) {
            return null;
        }
        return versionedLib.get(version);
    }

    /**
     * Given the declaring package name, returns a list of static shared libraries on all versions.
     */
    @GuardedBy("mPm.mLock")
    @Override
    public @NonNull WatchedLongSparseArray<SharedLibraryInfo> getStaticLibraryInfos(
            @NonNull String declaringPackageName) {
        return mStaticLibsByDeclaringPackage.get(declaringPackageName);
    }

    @Nullable
    private PackageStateInternal getLibraryPackage(@NonNull Computer computer,
            @NonNull SharedLibraryInfo libInfo) {
        final VersionedPackage declaringPackage = libInfo.getDeclaringPackage();
        if (libInfo.isStatic()) {
            // Resolve the package name - we use synthetic package names internally
            final String internalPackageName = computer.resolveInternalPackageName(
                    declaringPackage.getPackageName(),
                    declaringPackage.getLongVersionCode());
            return computer.getPackageStateInternal(internalPackageName);
        }
        if (libInfo.isSdk()) {
            return computer.getPackageStateInternal(declaringPackage.getPackageName());
        }
        return null;
    }

    /**
     * Finds all unused shared libraries which have cached more than the given
     * {@code maxCachePeriod}. Deletes them one by one until the available storage space on the
     * device is larger than {@code neededSpace}.
     *
     * @param neededSpace A minimum available storage space the device needs to reach
     * @param maxCachePeriod A maximum period of time an unused shared library can be cached
     *                       on the device.
     * @return {@code true} if the available storage space is reached.
     */
    boolean pruneUnusedStaticSharedLibraries(@NonNull Computer computer, long neededSpace,
            long maxCachePeriod)
            throws IOException {
        final StorageManager storage = mInjector.getSystemService(StorageManager.class);
        final File volume = storage.findPathForUuid(StorageManager.UUID_PRIVATE_INTERNAL);

        final ArrayList<VersionedPackage> packagesToDelete = new ArrayList<>();
        final long now = System.currentTimeMillis();

        // Important: We skip shared libs used for some user since
        // in such a case we need to keep the APK on the device. The check for
        // a lib being used for any user is performed by the uninstall call.
        final WatchedArrayMap<String, WatchedLongSparseArray<SharedLibraryInfo>>
                sharedLibraries = computer.getSharedLibraries();
        final int libCount = sharedLibraries.size();
        for (int i = 0; i < libCount; i++) {
            final WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                    sharedLibraries.valueAt(i);
            if (versionedLib == null) {
                continue;
            }
            final int versionCount = versionedLib.size();
            for (int j = 0; j < versionCount; j++) {
                SharedLibraryInfo libInfo = versionedLib.valueAt(j);
                final PackageStateInternal ps = getLibraryPackage(computer, libInfo);
                if (ps == null) {
                    continue;
                }
                // Skip unused libs cached less than the min period to prevent pruning a lib
                // needed by a subsequently installed package.
                if (now - ps.getLastUpdateTime() < maxCachePeriod) {
                    continue;
                }

                if (ps.getPkg().isSystem()) {
                    continue;
                }

                packagesToDelete.add(new VersionedPackage(ps.getPkg().getPackageName(),
                        libInfo.getDeclaringPackage().getLongVersionCode()));
            }
        }

        final int packageCount = packagesToDelete.size();
        for (int i = 0; i < packageCount; i++) {
            final VersionedPackage pkgToDelete = packagesToDelete.get(i);
            // Delete the package synchronously (will fail of the lib used for any user).
            if (mDeletePackageHelper.deletePackageX(pkgToDelete.getPackageName(),
                    pkgToDelete.getLongVersionCode(), UserHandle.USER_SYSTEM,
                    PackageManager.DELETE_ALL_USERS,
                    true /*removedBySystem*/) == PackageManager.DELETE_SUCCEEDED) {
                if (volume.getUsableSpace() >= neededSpace) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Given a package of static shared library, returns its shared library info of
     * the latest version.
     *
     * @param pkg A package of static shared library.
     * @return The latest version of shared library info.
     */
    @GuardedBy("mPm.mLock")
    @Nullable SharedLibraryInfo getLatestStaticSharedLibraVersionLPr(@NonNull AndroidPackage pkg) {
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(
                pkg.getStaticSharedLibName());
        if (versionedLib == null) {
            return null;
        }
        long previousLibVersion = -1;
        final int versionCount = versionedLib.size();
        for (int i = 0; i < versionCount; i++) {
            final long libVersion = versionedLib.keyAt(i);
            if (libVersion < pkg.getStaticSharedLibVersion()) {
                previousLibVersion = Math.max(previousLibVersion, libVersion);
            }
        }
        if (previousLibVersion >= 0) {
            return versionedLib.get(previousLibVersion);
        }
        return null;
    }

    /**
     * Given a package scanned result of a static shared library, returns its package setting of
     * the latest version
     *
     * @param scanResult The scanned result of a static shared library package.
     * @return The package setting that represents the latest version of shared library info.
     */
    @Nullable
    PackageSetting getStaticSharedLibLatestVersionSetting(@NonNull ScanResult scanResult) {
        PackageSetting sharedLibPackage = null;
        synchronized (mPm.mLock) {
            final SharedLibraryInfo latestSharedLibraVersionLPr =
                    getLatestStaticSharedLibraVersionLPr(scanResult.mRequest.mParsedPackage);
            if (latestSharedLibraVersionLPr != null) {
                sharedLibPackage = mPm.mSettings.getPackageLPr(
                        latestSharedLibraVersionLPr.getPackageName());
            }
        }
        return sharedLibPackage;
    }

    /**
     * Apply a given {@code action} to all the libraries defining in the package.
     *
     * @param pkg A package defining libraries.
     * @param libInfo An extra shared library info passing to the action.
     * @param action The action to apply.
     */
    @GuardedBy("mPm.mLock")
    private void applyDefiningSharedLibraryUpdateLPr(
            @NonNull AndroidPackage pkg, @Nullable SharedLibraryInfo libInfo,
            @NonNull BiConsumer<SharedLibraryInfo, SharedLibraryInfo> action) {
        // Note that libraries defined by this package may be null if:
        // - Package manager was unable to create the shared library. The package still
        //   gets installed, but the shared library does not get created.
        // Or:
        // - Package manager is in a state where package isn't scanned yet. This will
        //   get called again after scanning to fix the dependencies.
        if (AndroidPackageUtils.isLibrary(pkg)) {
            if (pkg.getSdkLibName() != null) {
                SharedLibraryInfo definedLibrary = getSharedLibraryInfo(
                        pkg.getSdkLibName(), pkg.getSdkLibVersionMajor());
                if (definedLibrary != null) {
                    action.accept(definedLibrary, libInfo);
                }
            } else if (pkg.getStaticSharedLibName() != null) {
                SharedLibraryInfo definedLibrary = getSharedLibraryInfo(
                        pkg.getStaticSharedLibName(), pkg.getStaticSharedLibVersion());
                if (definedLibrary != null) {
                    action.accept(definedLibrary, libInfo);
                }
            } else {
                for (String libraryName : pkg.getLibraryNames()) {
                    SharedLibraryInfo definedLibrary = getSharedLibraryInfo(
                            libraryName, SharedLibraryInfo.VERSION_UNDEFINED);
                    if (definedLibrary != null) {
                        action.accept(definedLibrary, libInfo);
                    }
                }
            }
        }
    }

    /**
     * Adds shared library {@code libInfo}'s self code paths and using library files to the list
     * {@code usesLibraryFiles}. Also, adds the dependencies to the shared libraries that are
     * defining in the {@code pkg}.
     *
     * @param pkg A package that is using the {@code libInfo}.
     * @param usesLibraryFiles A list to add code paths to.
     * @param libInfo A shared library info that is used by the {@code pkg}.
     * @param changingLib The updating library package.
     * @param changingLibSetting The updating library package setting.
     */
    @GuardedBy("mPm.mLock")
    private void addSharedLibraryLPr(@NonNull AndroidPackage pkg,
            @NonNull Set<String> usesLibraryFiles, @NonNull SharedLibraryInfo libInfo,
            @Nullable AndroidPackage changingLib, @Nullable PackageSetting changingLibSetting) {
        if (libInfo.getPath() != null) {
            usesLibraryFiles.add(libInfo.getPath());
            return;
        }
        AndroidPackage pkgForCodePaths = mPm.mPackages.get(libInfo.getPackageName());
        PackageSetting pkgSetting = mPm.mSettings.getPackageLPr(libInfo.getPackageName());
        if (changingLib != null && changingLib.getPackageName().equals(libInfo.getPackageName())) {
            // If we are doing this while in the middle of updating a library apk,
            // then we need to make sure to use that new apk for determining the
            // dependencies here.  (We haven't yet finished committing the new apk
            // to the package manager state.)
            if (pkgForCodePaths == null
                    || pkgForCodePaths.getPackageName().equals(changingLib.getPackageName())) {
                pkgForCodePaths = changingLib;
                pkgSetting = changingLibSetting;
            }
        }
        if (pkgForCodePaths != null) {
            usesLibraryFiles.addAll(AndroidPackageUtils.getAllCodePaths(pkgForCodePaths));
            // If the package provides libraries, add the dependency to them.
            applyDefiningSharedLibraryUpdateLPr(pkg, libInfo, SharedLibraryInfo::addDependency);
            if (pkgSetting != null) {
                usesLibraryFiles.addAll(pkgSetting.getPkgState().getUsesLibraryFiles());
            }
        }
    }

    /**
     * Collects all shared libraries being used by the target package. Rebuilds the dependencies
     * of shared libraries and update the correct shared library code paths for it.
     *
     * @param pkg The target package to update shared library dependency.
     * @param pkgSetting The target's package setting.
     * @param changingLib The updating library package.
     * @param changingLibSetting The updating library package setting.
     * @param availablePackages All installed packages and current being installed packages.
     */
    @GuardedBy("mPm.mLock")
    void updateSharedLibrariesLPw(@NonNull AndroidPackage pkg, @NonNull PackageSetting pkgSetting,
            @Nullable AndroidPackage changingLib, @Nullable PackageSetting changingLibSetting,
            @NonNull Map<String, AndroidPackage> availablePackages)
            throws PackageManagerException {
        final ArrayList<SharedLibraryInfo> sharedLibraryInfos = collectSharedLibraryInfos(
                pkg, availablePackages, null /* newLibraries */);
        executeSharedLibrariesUpdateLPw(pkg, pkgSetting, changingLib, changingLibSetting,
                sharedLibraryInfos, mPm.mUserManager.getUserIds());
    }

    /**
     * Rebuilds the dependencies of shared libraries for the target package, and update the
     * shared library code paths to its package setting.
     *
     * @param pkg The target package to update shared library dependency.
     * @param pkgSetting The target's package setting.
     * @param changingLib The updating library package.
     * @param changingLibSetting The updating library package setting.
     * @param usesLibraryInfos The shared libraries used by the target package.
     * @param allUsers All user ids on the device.
     */
    @GuardedBy("mPm.mLock")
    void executeSharedLibrariesUpdateLPw(AndroidPackage pkg,
            @NonNull PackageSetting pkgSetting, @Nullable AndroidPackage changingLib,
            @Nullable PackageSetting changingLibSetting,
            ArrayList<SharedLibraryInfo> usesLibraryInfos, int[] allUsers) {
        // If the package provides libraries, clear their old dependencies.
        // This method will set them up again.
        applyDefiningSharedLibraryUpdateLPr(pkg, null, (definingLibrary, dependency) -> {
            definingLibrary.clearDependencies();
        });
        if (usesLibraryInfos != null) {
            pkgSetting.getPkgState().setUsesLibraryInfos(usesLibraryInfos);
            // Use LinkedHashSet to preserve the order of files added to
            // usesLibraryFiles while eliminating duplicates.
            Set<String> usesLibraryFiles = new LinkedHashSet<>();
            for (SharedLibraryInfo libInfo : usesLibraryInfos) {
                addSharedLibraryLPr(pkg, usesLibraryFiles, libInfo, changingLib,
                        changingLibSetting);
            }
            pkgSetting.setPkgStateLibraryFiles(usesLibraryFiles);

            // let's make sure we mark all static shared libraries as installed for the same users
            // that its dependent packages are installed for.
            int[] installedUsers = new int[allUsers.length];
            int installedUserCount = 0;
            for (int u = 0; u < allUsers.length; u++) {
                if (pkgSetting.getInstalled(allUsers[u])) {
                    installedUsers[installedUserCount++] = allUsers[u];
                }
            }
            for (SharedLibraryInfo sharedLibraryInfo : usesLibraryInfos) {
                if (!sharedLibraryInfo.isStatic()) {
                    continue;
                }
                final PackageSetting staticLibPkgSetting =
                        mPm.getPackageSettingForMutation(sharedLibraryInfo.getPackageName());
                if (staticLibPkgSetting == null) {
                    Slog.wtf(TAG, "Shared lib without setting: " + sharedLibraryInfo);
                    continue;
                }
                for (int u = 0; u < installedUserCount; u++) {
                    staticLibPkgSetting.setInstalled(true, installedUsers[u]);
                }
            }
        } else {
            pkgSetting.getPkgState().setUsesLibraryInfos(Collections.emptyList())
                    .setUsesLibraryFiles(Collections.emptyList());
        }
    }

    private static boolean hasString(List<String> list, List<String> which) {
        if (list == null || which == null) {
            return false;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            for (int j = which.size() - 1; j >= 0; j--) {
                if (which.get(j).equals(list.get(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Update shared library dependencies and code paths for applications that are using the
     * library {@code updatedPkg}. Update all applications if the {@code updatedPkg} is null.
     *
     * @param updatedPkg The updating shared library package.
     * @param updatedPkgSetting The updating shared library package setting.
     * @param availablePackages All available packages on the device.
     * @return Packages that has been updated.
     */
    @GuardedBy("mPm.mLock")
    @Nullable ArrayList<AndroidPackage> updateAllSharedLibrariesLPw(
            @Nullable AndroidPackage updatedPkg, @Nullable PackageSetting updatedPkgSetting,
            @NonNull Map<String, AndroidPackage> availablePackages) {
        ArrayList<AndroidPackage> resultList = null;
        // Set of all descendants of a library; used to eliminate cycles
        ArraySet<String> descendants = null;
        // The current list of packages that need updating
        List<Pair<AndroidPackage, PackageSetting>> needsUpdating = null;
        if (updatedPkg != null && updatedPkgSetting != null) {
            needsUpdating = new ArrayList<>(1);
            needsUpdating.add(Pair.create(updatedPkg, updatedPkgSetting));
        }
        do {
            final Pair<AndroidPackage, PackageSetting> changingPkgPair =
                    (needsUpdating == null) ? null : needsUpdating.remove(0);
            final AndroidPackage changingPkg = changingPkgPair != null
                    ? changingPkgPair.first : null;
            final PackageSetting changingPkgSetting = changingPkgPair != null
                    ? changingPkgPair.second : null;
            for (int i = mPm.mPackages.size() - 1; i >= 0; --i) {
                final AndroidPackage pkg = mPm.mPackages.valueAt(i);
                final PackageSetting pkgSetting = mPm.mSettings.getPackageLPr(pkg.getPackageName());
                if (changingPkg != null
                        && !hasString(pkg.getUsesLibraries(), changingPkg.getLibraryNames())
                        && !hasString(pkg.getUsesOptionalLibraries(), changingPkg.getLibraryNames())
                        && !ArrayUtils.contains(pkg.getUsesStaticLibraries(),
                        changingPkg.getStaticSharedLibName())
                        && !ArrayUtils.contains(pkg.getUsesSdkLibraries(),
                        changingPkg.getSdkLibName())) {
                    continue;
                }
                if (resultList == null) {
                    resultList = new ArrayList<>();
                }
                resultList.add(pkg);
                // if we're updating a shared library, all of its descendants must be updated
                if (changingPkg != null) {
                    if (descendants == null) {
                        descendants = new ArraySet<>();
                    }
                    if (!descendants.contains(pkg.getPackageName())) {
                        descendants.add(pkg.getPackageName());
                        needsUpdating.add(Pair.create(pkg, pkgSetting));
                    }
                }
                try {
                    updateSharedLibrariesLPw(pkg, pkgSetting, changingPkg,
                            changingPkgSetting, availablePackages);
                } catch (PackageManagerException e) {
                    // If a system app update or an app and a required lib missing we
                    // delete the package and for updated system apps keep the data as
                    // it is better for the user to reinstall than to be in an limbo
                    // state. Also libs disappearing under an app should never happen
                    // - just in case.
                    if (!pkg.isSystem() || pkgSetting.getPkgState().isUpdatedSystemApp()) {
                        final int flags = pkgSetting.getPkgState().isUpdatedSystemApp()
                                ? PackageManager.DELETE_KEEP_DATA : 0;
                        mDeletePackageHelper.deletePackageLIF(pkg.getPackageName(), null, true,
                                mPm.mUserManager.getUserIds(), flags, null,
                                true);
                    }
                    Slog.e(TAG, "updateAllSharedLibrariesLPw failed: " + e.getMessage());
                }
            }
        } while (needsUpdating != null && needsUpdating.size() > 0);
        return resultList;
    }

    /**
     * Add a build-in shared library info by given system configuration.
     */
    @GuardedBy("mPm.mLock")
    void addBuiltInSharedLibraryLPw(@NonNull SystemConfig.SharedLibraryEntry entry) {
        // check if built-in or dynamic library exists
        if (getSharedLibraryInfo(entry.name, SharedLibraryInfo.VERSION_UNDEFINED) != null) {
            return;
        }

        SharedLibraryInfo libraryInfo = new SharedLibraryInfo(entry.filename, null, null,
                entry.name, SharedLibraryInfo.VERSION_UNDEFINED,
                SharedLibraryInfo.TYPE_BUILTIN,
                new VersionedPackage(PLATFORM_PACKAGE_NAME, 0L), null, null,
                entry.isNative);

        commitSharedLibraryInfoLPw(libraryInfo);
    }

    /**
     * Add a shared library info to the system. This is invoked when the package is being added or
     * scanned.
     */
    @GuardedBy("mPm.mLock")
    void commitSharedLibraryInfoLPw(@NonNull SharedLibraryInfo libraryInfo) {
        final String name = libraryInfo.getName();
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(name);
        if (versionedLib == null) {
            versionedLib = new WatchedLongSparseArray<>();
            mSharedLibraries.put(name, versionedLib);
        }
        final String declaringPackageName = libraryInfo.getDeclaringPackage().getPackageName();
        if (libraryInfo.getType() == SharedLibraryInfo.TYPE_STATIC) {
            mStaticLibsByDeclaringPackage.put(declaringPackageName, versionedLib);
        }
        versionedLib.put(libraryInfo.getLongVersion(), libraryInfo);
    }

    /**
     * Remove a shared library from the system.
     */
    @GuardedBy("mPm.mLock")
    boolean removeSharedLibraryLPw(@NonNull String libName, long version) {
        WatchedLongSparseArray<SharedLibraryInfo> versionedLib = mSharedLibraries.get(libName);
        if (versionedLib == null) {
            return false;
        }
        final int libIdx = versionedLib.indexOfKey(version);
        if (libIdx < 0) {
            return false;
        }
        SharedLibraryInfo libraryInfo = versionedLib.valueAt(libIdx);

        final Computer snapshot = mPm.snapshotComputer();

        // Remove the shared library overlays from its dependent packages.
        for (int currentUserId : mPm.mUserManager.getUserIds()) {
            final List<VersionedPackage> dependents = snapshot.getPackagesUsingSharedLibrary(
                    libraryInfo, 0, Process.SYSTEM_UID, currentUserId);
            if (dependents == null) {
                continue;
            }
            for (VersionedPackage dependentPackage : dependents) {
                final PackageSetting ps = mPm.mSettings.getPackageLPr(
                        dependentPackage.getPackageName());
                if (ps != null) {
                    ps.setOverlayPathsForLibrary(libraryInfo.getName(), null, currentUserId);
                }
            }
        }

        versionedLib.remove(version);
        if (versionedLib.size() <= 0) {
            mSharedLibraries.remove(libName);
            if (libraryInfo.getType() == SharedLibraryInfo.TYPE_STATIC) {
                mStaticLibsByDeclaringPackage.remove(libraryInfo.getDeclaringPackage()
                        .getPackageName());
            }
        }
        return true;
    }

    /**
     * Compare the newly scanned package with current system state to see which of its declared
     * shared libraries should be allowed to be added to the system.
     */
    List<SharedLibraryInfo> getAllowedSharedLibInfos(ScanResult scanResult) {
        // Let's used the parsed package as scanResult.pkgSetting may be null
        final ParsedPackage parsedPackage = scanResult.mRequest.mParsedPackage;
        if (scanResult.mSdkSharedLibraryInfo == null && scanResult.mStaticSharedLibraryInfo == null
                && scanResult.mDynamicSharedLibraryInfos == null) {
            return null;
        }

        // Any app can add new SDKs and static shared libraries.
        if (scanResult.mSdkSharedLibraryInfo != null) {
            return Collections.singletonList(scanResult.mSdkSharedLibraryInfo);
        }
        if (scanResult.mStaticSharedLibraryInfo != null) {
            return Collections.singletonList(scanResult.mStaticSharedLibraryInfo);
        }
        final boolean hasDynamicLibraries = parsedPackage.isSystem()
                && scanResult.mDynamicSharedLibraryInfos != null;
        if (!hasDynamicLibraries) {
            return null;
        }
        final boolean isUpdatedSystemApp = scanResult.mPkgSetting.getPkgState()
                .isUpdatedSystemApp();
        // We may not yet have disabled the updated package yet, so be sure to grab the
        // current setting if that's the case.
        final PackageSetting updatedSystemPs = isUpdatedSystemApp
                ? scanResult.mRequest.mDisabledPkgSetting == null
                ? scanResult.mRequest.mOldPkgSetting
                : scanResult.mRequest.mDisabledPkgSetting
                : null;
        if (isUpdatedSystemApp && (updatedSystemPs.getPkg() == null
                || updatedSystemPs.getPkg().getLibraryNames() == null)) {
            Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                    + " declares libraries that are not declared on the system image; skipping");
            return null;
        }
        final ArrayList<SharedLibraryInfo> infos =
                new ArrayList<>(scanResult.mDynamicSharedLibraryInfos.size());
        for (SharedLibraryInfo info : scanResult.mDynamicSharedLibraryInfos) {
            final String name = info.getName();
            if (isUpdatedSystemApp) {
                // New library entries can only be added through the
                // system image.  This is important to get rid of a lot
                // of nasty edge cases: for example if we allowed a non-
                // system update of the app to add a library, then uninstalling
                // the update would make the library go away, and assumptions
                // we made such as through app install filtering would now
                // have allowed apps on the device which aren't compatible
                // with it.  Better to just have the restriction here, be
                // conservative, and create many fewer cases that can negatively
                // impact the user experience.
                if (!updatedSystemPs.getPkg().getLibraryNames().contains(name)) {
                    Slog.w(TAG, "Package " + parsedPackage.getPackageName()
                            + " declares library " + name
                            + " that is not declared on system image; skipping");
                    continue;
                }
            }
            synchronized (mPm.mLock) {
                if (getSharedLibraryInfo(name, SharedLibraryInfo.VERSION_UNDEFINED) != null) {
                    Slog.w(TAG, "Package " + parsedPackage.getPackageName() + " declares library "
                            + name + " that already exists; skipping");
                    continue;
                }
            }
            infos.add(info);
        }
        return infos;
    }

    /**
     * Collects shared library infos that are being used by the given package.
     *
     * @param pkg The package using shared libraries.
     * @param availablePackages The available packages which are installed and being installed,
     * @param newLibraries Shared libraries defined by packages which are being installed.
     * @return A list of shared library infos
     */
    ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(@Nullable AndroidPackage pkg,
            @NonNull Map<String, AndroidPackage> availablePackages,
            @Nullable final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries)
            throws PackageManagerException {
        if (pkg == null) {
            return null;
        }
        final PlatformCompat platformCompat = mInjector.getCompatibility();
        // The collection used here must maintain the order of addition (so
        // that libraries are searched in the correct order) and must have no
        // duplicates.
        ArrayList<SharedLibraryInfo> usesLibraryInfos = null;
        if (!pkg.getUsesLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesLibraries(), null, null,
                    pkg.getPackageName(), "shared", true, pkg.getTargetSdkVersion(), null,
                    availablePackages, newLibraries);
        }
        if (!pkg.getUsesStaticLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesStaticLibraries(),
                    pkg.getUsesStaticLibrariesVersions(), pkg.getUsesStaticLibrariesCertDigests(),
                    pkg.getPackageName(), "static shared", true, pkg.getTargetSdkVersion(),
                    usesLibraryInfos, availablePackages, newLibraries);
        }
        if (!pkg.getUsesOptionalLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesOptionalLibraries(), null, null,
                    pkg.getPackageName(), "shared", false, pkg.getTargetSdkVersion(),
                    usesLibraryInfos, availablePackages, newLibraries);
        }
        if (platformCompat.isChangeEnabledInternal(ENFORCE_NATIVE_SHARED_LIBRARY_DEPENDENCIES,
                pkg.getPackageName(), pkg.getTargetSdkVersion())) {
            if (!pkg.getUsesNativeLibraries().isEmpty()) {
                usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesNativeLibraries(), null,
                        null, pkg.getPackageName(), "native shared", true,
                        pkg.getTargetSdkVersion(), usesLibraryInfos, availablePackages,
                        newLibraries);
            }
            if (!pkg.getUsesOptionalNativeLibraries().isEmpty()) {
                usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesOptionalNativeLibraries(),
                        null, null, pkg.getPackageName(), "native shared", false,
                        pkg.getTargetSdkVersion(), usesLibraryInfos, availablePackages,
                        newLibraries);
            }
        }
        if (!pkg.getUsesSdkLibraries().isEmpty()) {
            usesLibraryInfos = collectSharedLibraryInfos(pkg.getUsesSdkLibraries(),
                    pkg.getUsesSdkLibrariesVersionsMajor(), pkg.getUsesSdkLibrariesCertDigests(),
                    pkg.getPackageName(), "sdk", true, pkg.getTargetSdkVersion(), usesLibraryInfos,
                    availablePackages, newLibraries);
        }
        return usesLibraryInfos;
    }

    private ArrayList<SharedLibraryInfo> collectSharedLibraryInfos(
            @NonNull List<String> requestedLibraries,
            @Nullable long[] requiredVersions, @Nullable String[][] requiredCertDigests,
            @NonNull String packageName, @NonNull String libraryType, boolean required,
            int targetSdk, @Nullable ArrayList<SharedLibraryInfo> outUsedLibraries,
            @NonNull final Map<String, AndroidPackage> availablePackages,
            @Nullable final Map<String, WatchedLongSparseArray<SharedLibraryInfo>> newLibraries)
            throws PackageManagerException {
        final int libCount = requestedLibraries.size();
        for (int i = 0; i < libCount; i++) {
            final String libName = requestedLibraries.get(i);
            final long libVersion = requiredVersions != null ? requiredVersions[i]
                    : SharedLibraryInfo.VERSION_UNDEFINED;
            final SharedLibraryInfo libraryInfo;
            synchronized (mPm.mLock) {
                libraryInfo = SharedLibraryUtils.getSharedLibraryInfo(
                        libName, libVersion, mSharedLibraries, newLibraries);
            }
            if (libraryInfo == null) {
                if (required) {
                    throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                            "Package " + packageName + " requires unavailable " + libraryType
                                    + " library " + libName + "; failing!");
                } else if (DEBUG_SHARED_LIBRARIES) {
                    Slog.i(TAG, "Package " + packageName + " desires unavailable " + libraryType
                            + " library " + libName + "; ignoring!");
                }
            } else {
                if (requiredVersions != null && requiredCertDigests != null) {
                    if (libraryInfo.getLongVersion() != requiredVersions[i]) {
                        throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                "Package " + packageName + " requires unavailable " + libraryType
                                        + " library " + libName + " version "
                                        + libraryInfo.getLongVersion() + "; failing!");
                    }
                    AndroidPackage pkg = availablePackages.get(libraryInfo.getPackageName());
                    SigningDetails libPkg = pkg == null ? null : pkg.getSigningDetails();
                    if (libPkg == null) {
                        throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                "Package " + packageName + " requires unavailable " + libraryType
                                        + " library; failing!");
                    }
                    final String[] expectedCertDigests = requiredCertDigests[i];
                    if (expectedCertDigests.length > 1) {
                        // For apps targeting O MR1 we require explicit enumeration of all certs.
                        final String[] libCertDigests = (targetSdk >= Build.VERSION_CODES.O_MR1)
                                ? PackageUtils.computeSignaturesSha256Digests(
                                libPkg.getSignatures())
                                : PackageUtils.computeSignaturesSha256Digests(
                                        new Signature[]{libPkg.getSignatures()[0]});

                        // Take a shortcut if sizes don't match. Note that if an app doesn't
                        // target O we don't parse the "additional-certificate" tags similarly
                        // how we only consider all certs only for apps targeting O (see above).
                        // Therefore, the size check is safe to make.
                        if (expectedCertDigests.length != libCertDigests.length) {
                            throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                    "Package " + packageName + " requires differently signed "
                                            + libraryType + " library; failing!");
                        }

                        // Use a predictable order as signature order may vary
                        Arrays.sort(libCertDigests);
                        Arrays.sort(expectedCertDigests);

                        final int certCount = libCertDigests.length;
                        for (int j = 0; j < certCount; j++) {
                            if (!libCertDigests[j].equalsIgnoreCase(expectedCertDigests[j])) {
                                throw new PackageManagerException(
                                        INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                        "Package " + packageName + " requires differently signed "
                                                + libraryType + " library; failing!");
                            }
                        }
                    } else {
                        // lib signing cert could have rotated beyond the one expected, check to see
                        // if the new one has been blessed by the old
                        byte[] digestBytes = HexEncoding.decode(
                                expectedCertDigests[0], false /* allowSingleChar */);
                        if (!libPkg.hasSha256Certificate(digestBytes)) {
                            throw new PackageManagerException(INSTALL_FAILED_MISSING_SHARED_LIBRARY,
                                    "Package " + packageName + " requires differently signed "
                                            + libraryType + " library; failing!");
                        }
                    }
                }
                if (outUsedLibraries == null) {
                    outUsedLibraries = new ArrayList<>();
                }
                outUsedLibraries.add(libraryInfo);
            }
        }
        return outUsedLibraries;
    }

    /**
     * Dump all shared libraries.
     */
    @GuardedBy("mPm.mLock")
    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull DumpState dumpState) {
        final boolean checkin = dumpState.isCheckIn();
        boolean printedHeader = false;
        final int numSharedLibraries = mSharedLibraries.size();
        for (int index = 0; index < numSharedLibraries; index++) {
            final String libName = mSharedLibraries.keyAt(index);
            final WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                    mSharedLibraries.get(libName);
            if (versionedLib == null) {
                continue;
            }
            final int versionCount = versionedLib.size();
            for (int i = 0; i < versionCount; i++) {
                SharedLibraryInfo libraryInfo = versionedLib.valueAt(i);
                if (!checkin) {
                    if (!printedHeader) {
                        if (dumpState.onTitlePrinted()) {
                            pw.println();
                        }
                        pw.println("Libraries:");
                        printedHeader = true;
                    }
                    pw.print("  ");
                } else {
                    pw.print("lib,");
                }
                pw.print(libraryInfo.getName());
                if (libraryInfo.isStatic()) {
                    pw.print(" version=" + libraryInfo.getLongVersion());
                }
                if (!checkin) {
                    pw.print(" -> ");
                }
                if (libraryInfo.getPath() != null) {
                    if (libraryInfo.isNative()) {
                        pw.print(" (so) ");
                    } else {
                        pw.print(" (jar) ");
                    }
                    pw.print(libraryInfo.getPath());
                } else {
                    pw.print(" (apk) ");
                    pw.print(libraryInfo.getPackageName());
                }
                pw.println();
            }
        }
    }

    /**
     * Dump all shared libraries to given proto output stream.
     */
    @GuardedBy("mPm.mLock")
    @Override
    public void dumpProto(@NonNull ProtoOutputStream proto) {
        final int count = mSharedLibraries.size();
        for (int i = 0; i < count; i++) {
            final String libName = mSharedLibraries.keyAt(i);
            WatchedLongSparseArray<SharedLibraryInfo> versionedLib =
                    mSharedLibraries.get(libName);
            if (versionedLib == null) {
                continue;
            }
            final int versionCount = versionedLib.size();
            for (int j = 0; j < versionCount; j++) {
                final SharedLibraryInfo libraryInfo = versionedLib.valueAt(j);
                final long sharedLibraryToken =
                        proto.start(PackageServiceDumpProto.SHARED_LIBRARIES);
                proto.write(PackageServiceDumpProto.SharedLibraryProto.NAME, libraryInfo.getName());
                final boolean isJar = (libraryInfo.getPath() != null);
                proto.write(PackageServiceDumpProto.SharedLibraryProto.IS_JAR, isJar);
                if (isJar) {
                    proto.write(PackageServiceDumpProto.SharedLibraryProto.PATH,
                            libraryInfo.getPath());
                } else {
                    proto.write(PackageServiceDumpProto.SharedLibraryProto.APK,
                            libraryInfo.getPackageName());
                }
                proto.end(sharedLibraryToken);
            }
        }
    }
}
