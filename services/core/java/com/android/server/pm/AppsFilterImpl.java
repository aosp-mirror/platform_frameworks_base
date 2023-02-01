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
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_NULL;
import static android.provider.DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED__EVENT_TYPE__BOOT;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED__EVENT_TYPE__USER_CREATED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED__EVENT_TYPE__USER_DELETED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__COMPAT_CHANGED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__PACKAGE_ADDED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__PACKAGE_DELETED;
import static com.android.internal.util.FrameworkStatsLog.PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__PACKAGE_REPLACED;
import static com.android.server.pm.AppsFilterUtils.canQueryAsInstaller;
import static com.android.server.pm.AppsFilterUtils.canQueryAsUpdateOwner;
import static com.android.server.pm.AppsFilterUtils.canQueryViaComponents;
import static com.android.server.pm.AppsFilterUtils.canQueryViaPackage;
import static com.android.server.pm.AppsFilterUtils.canQueryViaUsesLibrary;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.FgThread;
import com.android.server.compat.CompatChange;
import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.AppsFilterUtils.ParallelComputeComponentVisibility;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedPermission;
import com.android.server.pm.pkg.component.ParsedUsesPermission;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.WatchedSparseBooleanMatrix;
import com.android.server.utils.WatchedSparseSetArray;
import com.android.server.utils.Watcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of the methods that update the internal structures of AppsFilter. Because of the
 * mutations, all the read accesses to those internal structures need to be locked, thus extending
 * {@link AppsFilterLocked}.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class AppsFilterImpl extends AppsFilterLocked implements Watchable, Snappable {
    /**
     * A cached snapshot.
     */
    @NonNull
    private final SnapshotCache<AppsFilterSnapshot> mSnapshot;

    /**
     * Watchable machinery
     */
    private final WatchableImpl mWatchable = new WatchableImpl();

    /**
     * A cache that maps parsed {@link android.R.styleable#AndroidManifestPermission
     * &lt;permission&gt;} to the packages that define them. While computing visibility based on
     * permissions, this cache is used to save the cost of reading through every existing package
     * to determine the App Ids that define a particular permission.
     */
    @GuardedBy("mQueryableViaUsesPermissionLock")
    @NonNull
    private final ArrayMap<String, ArraySet<Integer>> mPermissionToUids;

    /**
     * A cache that maps parsed {@link android.R.styleable#AndroidManifestUsesPermission
     * &lt;uses-permission&gt;} to the packages that request them. While computing visibility based
     * on permissions, this cache is used to save the cost of reading through every existing
     * package to determine the App Ids that request a particular permission.
     */
    @GuardedBy("mQueryableViaUsesPermissionLock")
    @NonNull
    private final ArrayMap<String, ArraySet<Integer>> mUsesPermissionToUids;

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
     * Return true if the {@link Watcher) is a registered observer.
     *
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
     * Report a change to observers.
     */
    private void onChanged() {
        dispatchChange(this);
    }

    private void invalidateCache(String reason) {
        if (mCacheValid.compareAndSet(CACHE_VALID, CACHE_INVALID)) {
            Slog.i(TAG, "Invalidating cache: " + reason);
        }
    }

    @VisibleForTesting(visibility = PRIVATE)
    AppsFilterImpl(FeatureConfig featureConfig,
            String[] forceQueryableList,
            boolean systemAppsQueryable,
            @Nullable OverlayReferenceMapper.Provider overlayProvider,
            Handler handler) {
        mFeatureConfig = featureConfig;
        mForceQueryableByDevicePackageNames = forceQueryableList;
        mSystemAppsQueryable = systemAppsQueryable;
        mOverlayReferenceMapper = new OverlayReferenceMapper(true /*deferRebuild*/,
                overlayProvider);
        mHandler = handler;
        mShouldFilterCache = new WatchedSparseBooleanMatrix();
        mShouldFilterCacheSnapshot = new SnapshotCache.Auto<>(
                mShouldFilterCache, mShouldFilterCache, "AppsFilter.mShouldFilterCache");
        mImplicitlyQueryable = new WatchedSparseSetArray<>();
        mImplicitQueryableSnapshot = new SnapshotCache.Auto<>(
                mImplicitlyQueryable, mImplicitlyQueryable, "AppsFilter.mImplicitlyQueryable");
        mRetainedImplicitlyQueryable = new WatchedSparseSetArray<>();
        mRetainedImplicitlyQueryableSnapshot = new SnapshotCache.Auto<>(
                mRetainedImplicitlyQueryable, mRetainedImplicitlyQueryable,
                "AppsFilter.mRetainedImplicitlyQueryable");
        mQueriesViaPackage = new WatchedSparseSetArray<>();
        mQueriesViaPackageSnapshot = new SnapshotCache.Auto<>(
                mQueriesViaPackage, mQueriesViaPackage, "AppsFilter.mQueriesViaPackage");
        mQueriesViaComponent = new WatchedSparseSetArray<>();
        mQueriesViaComponentSnapshot = new SnapshotCache.Auto<>(
                mQueriesViaComponent, mQueriesViaComponent, "AppsFilter.mQueriesViaComponent");
        mQueryableViaUsesLibrary = new WatchedSparseSetArray<>();
        mQueryableViaUsesLibrarySnapshot = new SnapshotCache.Auto<>(
                mQueryableViaUsesLibrary, mQueryableViaUsesLibrary,
                "AppsFilter.mQueryableViaUsesLibrary");
        mQueryableViaUsesPermission = new WatchedSparseSetArray<>();
        mQueryableViaUsesPermissionSnapshot = new SnapshotCache.Auto<>(
                mQueryableViaUsesPermission, mQueryableViaUsesPermission,
                "AppsFilter.mQueryableViaUsesPermission");
        mForceQueryable = new WatchedArraySet<>();
        mForceQueryableSnapshot = new SnapshotCache.Auto<>(
                mForceQueryable, mForceQueryable, "AppsFilter.mForceQueryable");
        mProtectedBroadcasts = new WatchedArraySet<>();
        mProtectedBroadcastsSnapshot = new SnapshotCache.Auto<>(
                mProtectedBroadcasts, mProtectedBroadcasts, "AppsFilter.mProtectedBroadcasts");
        mPermissionToUids = new ArrayMap<>();
        mUsesPermissionToUids = new ArrayMap<>();

        mSnapshot = new SnapshotCache<AppsFilterSnapshot>(this, this) {
            @Override
            public AppsFilterSnapshot createSnapshot() {
                return new AppsFilterSnapshotImpl(AppsFilterImpl.this);
            }
        };
        readCacheEnabledSysProp();
        SystemProperties.addChangeCallback(this::readCacheEnabledSysProp);
    }

    private void readCacheEnabledSysProp() {
        mCacheEnabled = SystemProperties.getBoolean("debug.pm.use_app_filter_cache", true);
    }

    /**
     * Return a snapshot.  If the cached snapshot is null, build a new one.  The logic in
     * the function ensures that this function returns a valid snapshot even if a race
     * condition causes the cached snapshot to be cleared asynchronously to this method.
     */
    public AppsFilterSnapshot snapshot() {
        return mSnapshot.snapshot();
    }

    private static class FeatureConfigImpl implements FeatureConfig,
            CompatChange.ChangeListener {
        private static final String FILTERING_ENABLED_NAME = "package_query_filtering_enabled";
        private final PackageManagerServiceInjector mInjector;
        private final PackageManagerInternal mPmInternal;
        private volatile boolean mFeatureEnabled =
                PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT;
        private final ArraySet<String> mDisabledPackages = new ArraySet<>();

        @Nullable
        private SparseBooleanArray mLoggingEnabled = null;
        private AppsFilterImpl mAppsFilter;

        private FeatureConfigImpl(
                PackageManagerInternal pmInternal, PackageManagerServiceInjector injector) {
            mPmInternal = pmInternal;
            mInjector = injector;
        }

        FeatureConfigImpl(FeatureConfigImpl orig) {
            mInjector = null;
            mPmInternal = null;
            mFeatureEnabled = orig.mFeatureEnabled;
            mDisabledPackages.addAll(orig.mDisabledPackages);
            mLoggingEnabled = orig.mLoggingEnabled;
        }

        public void setAppsFilter(AppsFilterImpl filter) {
            mAppsFilter = filter;
        }

        @Override
        public void onSystemReady() {
            mFeatureEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FILTERING_ENABLED_NAME,
                    PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT);
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_PACKAGE_MANAGER_SERVICE, FgThread.getExecutor(),
                    properties -> {
                        if (properties.getKeyset().contains(FILTERING_ENABLED_NAME)) {
                            synchronized (FeatureConfigImpl.this) {
                                mFeatureEnabled = properties.getBoolean(FILTERING_ENABLED_NAME,
                                        PackageManager.APP_ENUMERATION_ENABLED_BY_DEFAULT);
                            }
                        }
                    });
            mInjector.getCompatibility().registerListener(
                    PackageManager.FILTER_APPLICATION_QUERY, this);
        }

        @Override
        public boolean isGloballyEnabled() {
            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "isGloballyEnabled");
            }
            try {
                return mFeatureEnabled;
            } finally {
                if (DEBUG_TRACING) {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }
        }

        @Override
        public boolean packageIsEnabled(AndroidPackage pkg) {
            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "packageIsEnabled");
            }
            try {
                return !mDisabledPackages.contains(pkg.getPackageName());
            } finally {
                if (DEBUG_TRACING) {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }
        }

        @Override
        public boolean isLoggingEnabled(int uid) {
            return mLoggingEnabled != null && mLoggingEnabled.indexOfKey(uid) >= 0;
        }

        @Override
        public void enableLogging(int appId, boolean enable) {
            if (enable) {
                if (mLoggingEnabled == null) {
                    mLoggingEnabled = new SparseBooleanArray();
                }
                mLoggingEnabled.put(appId, true);
            } else {
                if (mLoggingEnabled != null) {
                    final int index = mLoggingEnabled.indexOfKey(appId);
                    if (index >= 0) {
                        mLoggingEnabled.removeAt(index);
                        if (mLoggingEnabled.size() == 0) {
                            mLoggingEnabled = null;
                        }
                    }
                }
            }
        }

        @Override
        public void onCompatChange(String packageName) {
            Computer snapshot = (Computer) mPmInternal.snapshot();
            AndroidPackage pkg = snapshot.getPackage(packageName);
            if (pkg == null) {
                return;
            }
            final long currentTimeUs = SystemClock.currentTimeMicro();
            updateEnabledState(pkg);
            mAppsFilter.updateShouldFilterCacheForPackage(snapshot, packageName);
            mAppsFilter.logCacheUpdated(
                    PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__COMPAT_CHANGED,
                    SystemClock.currentTimeMicro() - currentTimeUs,
                    snapshot.getUserInfos().length,
                    snapshot.getPackageStates().size(),
                    pkg.getUid());
        }

        private void updateEnabledState(@NonNull AndroidPackage pkg) {
            // TODO(b/135203078): Do not use toAppInfo
            // TODO(b/167551701): Make changeId non-logging
            final boolean enabled = mInjector.getCompatibility().isChangeEnabledInternalNoLogging(
                    PackageManager.FILTER_APPLICATION_QUERY,
                    AndroidPackageUtils.generateAppInfoWithoutState(pkg));
            if (enabled) {
                mDisabledPackages.remove(pkg.getPackageName());
            } else {
                mDisabledPackages.add(pkg.getPackageName());
            }
            if (mAppsFilter != null) {
                mAppsFilter.onChanged();
            }
        }

        @Override
        public void updatePackageState(PackageStateInternal setting, boolean removed) {
            final boolean enableLogging = setting.getPkg() != null
                    && !removed && (setting.getPkg().isTestOnly()
                    || setting.getPkg().isDebuggable());
            enableLogging(setting.getAppId(), enableLogging);
            if (removed) {
                mDisabledPackages.remove(setting.getPackageName());
                if (mAppsFilter != null) {
                    mAppsFilter.onChanged();
                }
            } else if (setting.getPkg() != null) {
                updateEnabledState(setting.getPkg());
            }
        }

        @Override
        public FeatureConfig snapshot() {
            return new FeatureConfigImpl(this);
        }
    }

    /** Builder method for an AppsFilter */
    public static AppsFilterImpl create(@NonNull PackageManagerServiceInjector injector,
            @NonNull PackageManagerInternal pmInt) {
        final boolean forceSystemAppsQueryable =
                injector.getContext().getResources()
                        .getBoolean(R.bool.config_forceSystemPackagesQueryable);
        final FeatureConfigImpl featureConfig = new FeatureConfigImpl(pmInt, injector);
        final String[] forcedQueryablePackageNames;
        if (forceSystemAppsQueryable) {
            // all system apps already queryable, no need to read and parse individual exceptions
            forcedQueryablePackageNames = new String[]{};
        } else {
            forcedQueryablePackageNames =
                    injector.getContext().getResources()
                            .getStringArray(R.array.config_forceQueryablePackages);
            for (int i = 0; i < forcedQueryablePackageNames.length; i++) {
                forcedQueryablePackageNames[i] = forcedQueryablePackageNames[i].intern();
            }
        }
        AppsFilterImpl appsFilter = new AppsFilterImpl(featureConfig,
                forcedQueryablePackageNames, forceSystemAppsQueryable, null,
                injector.getHandler());
        featureConfig.setAppsFilter(appsFilter);
        return appsFilter;
    }

    public FeatureConfig getFeatureConfig() {
        return mFeatureConfig;
    }

    /**
     * Grants access based on an interaction between a calling and target package, granting
     * visibility of the caller from the target.
     *
     * @param recipientUid   the uid gaining visibility of the {@code visibleUid}.
     * @param visibleUid     the uid becoming visible to the {@recipientUid}
     * @param retainOnUpdate if the implicit access retained across package updates.
     * @return {@code true} if implicit access was not already granted.
     */
    public boolean grantImplicitAccess(int recipientUid, int visibleUid, boolean retainOnUpdate) {
        if (recipientUid == visibleUid) {
            return false;
        }
        final boolean changed;
        synchronized (mImplicitlyQueryableLock) {
            changed = retainOnUpdate
                    ? mRetainedImplicitlyQueryable.add(recipientUid, visibleUid)
                    : mImplicitlyQueryable.add(recipientUid, visibleUid);
        }
        if (changed && DEBUG_LOGGING) {
            Slog.i(TAG, (retainOnUpdate ? "retained " : "") + "implicit access granted: "
                    + recipientUid + " -> " + visibleUid);
        }

        if (mCacheReady) {
            synchronized (mCacheLock) {
                // Update the cache in a one-off manner since we've got all the information we need.
                mShouldFilterCache.put(recipientUid, visibleUid, false);
            }
        } else if (changed) {
            invalidateCache("grantImplicitAccess: " + recipientUid + " -> " + visibleUid);
        }
        if (changed) {
            onChanged();
        }
        return changed;
    }

    public void onSystemReady(PackageManagerInternal pmInternal) {
        mOverlayReferenceMapper.rebuildIfDeferred();
        mFeatureConfig.onSystemReady();

        updateEntireShouldFilterCacheAsync(pmInternal,
                PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED__EVENT_TYPE__BOOT);
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkgSetting the new setting being added
     * @param isReplace     if the package is being replaced and may need extra cleanup.
     * @param retainImplicitGrantOnReplace {@code true} to retain implicit grant access if
     *                                     the package is being replaced.
     */
    public void addPackage(Computer snapshot, PackageStateInternal newPkgSetting,
            boolean isReplace, boolean retainImplicitGrantOnReplace) {
        final long currentTimeUs = SystemClock.currentTimeMicro();
        final int logType = isReplace
                ? PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__PACKAGE_REPLACED
                : PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__PACKAGE_ADDED;
        if (DEBUG_TRACING) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "filter.addPackage");
        }
        try {
            if (isReplace) {
                // let's first remove any prior rules for this package
                removePackageInternal(snapshot, newPkgSetting,
                        true /*isReplace*/, retainImplicitGrantOnReplace);
            }
            final ArrayMap<String, ? extends PackageStateInternal> settings =
                    snapshot.getPackageStates();
            final UserInfo[] users = snapshot.getUserInfos();
            final ArraySet<String> additionalChangedPackages =
                    addPackageInternal(newPkgSetting, settings);
            if (mCacheReady) {
                synchronized (mCacheLock) {
                    updateShouldFilterCacheForPackage(snapshot, null, newPkgSetting,
                            settings, users, USER_ALL, settings.size());
                    if (additionalChangedPackages != null) {
                        for (int index = 0; index < additionalChangedPackages.size(); index++) {
                            String changedPackage = additionalChangedPackages.valueAt(index);
                            PackageStateInternal changedPkgSetting = settings.get(changedPackage);
                            if (changedPkgSetting == null) {
                                // It's possible for the overlay mapper to know that an actor
                                // package changed via an explicit reference, even if the actor
                                // isn't installed, so skip if that's the case.
                                continue;
                            }
                            updateShouldFilterCacheForPackage(snapshot, null, changedPkgSetting,
                                    settings, users, USER_ALL, settings.size());
                        }
                    }
                }
                logCacheUpdated(logType, SystemClock.currentTimeMicro() - currentTimeUs,
                        users.length, settings.size(), newPkgSetting.getAppId());
            } else {
                invalidateCache("addPackage: " + newPkgSetting.getPackageName());
            }
        } finally {
            onChanged();
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }
    }

    /**
     * @return Additional packages that may have had their viewing visibility changed and may need
     * to be updated in the cache. Returns null if there are no additional packages.
     */
    @Nullable
    private ArraySet<String> addPackageInternal(PackageStateInternal newPkgSetting,
            ArrayMap<String, ? extends PackageStateInternal> existingSettings) {
        if (Objects.equals("android", newPkgSetting.getPackageName())) {
            // let's set aside the framework signatures
            mSystemSigningDetails = newPkgSetting.getSigningDetails();
            // and since we add overlays before we add the framework, let's revisit already added
            // packages for signature matches
            for (PackageStateInternal setting : existingSettings.values()) {
                if (isSystemSigned(mSystemSigningDetails, setting)) {
                    synchronized (mForceQueryableLock) {
                        mForceQueryable.add(setting.getAppId());
                    }
                }
            }
        }

        final AndroidPackage newPkg = newPkgSetting.getPkg();
        if (newPkg == null) {
            return null;
        }

        final List<String> newBroadcasts = newPkg.getProtectedBroadcasts();
        if (newBroadcasts.size() != 0) {
            final boolean protectedBroadcastsChanged;
            synchronized (mProtectedBroadcastsLock) {
                final int oldSize = mProtectedBroadcasts.size();
                mProtectedBroadcasts.addAll(newBroadcasts);
                protectedBroadcastsChanged = mProtectedBroadcasts.size() != oldSize;
            }
            if (protectedBroadcastsChanged) {
                mQueriesViaComponentRequireRecompute.set(true);
            }
        }

        final boolean newIsForceQueryable;
        synchronized (mForceQueryableLock) {
            newIsForceQueryable = mForceQueryable.contains(newPkgSetting.getAppId())
                            /* shared user that is already force queryable */
                            || newPkgSetting.isForceQueryableOverride() /* adb override */
                            || (newPkgSetting.isSystem() && (mSystemAppsQueryable
                            || newPkg.isForceQueryable()
                            || ArrayUtils.contains(mForceQueryableByDevicePackageNames,
                            newPkg.getPackageName())));
            if (newIsForceQueryable
                    || (mSystemSigningDetails != null
                    && isSystemSigned(mSystemSigningDetails, newPkgSetting))) {
                mForceQueryable.add(newPkgSetting.getAppId());
            }
        }

        if (!newPkg.getUsesPermissions().isEmpty()) {
            // newPkg requests some permissions
            synchronized (mQueryableViaUsesPermissionLock) {
                for (ParsedUsesPermission usesPermission : newPkg.getUsesPermissions()) {
                    String usesPermissionName = usesPermission.getName();
                    // Lookup in the mPermissionToUids cache if installed packages have
                    // defined this permission.
                    if (mPermissionToUids.containsKey(usesPermissionName)) {
                        final ArraySet<Integer> permissionDefiners =
                                mPermissionToUids.get(usesPermissionName);
                        for (int j = 0; j < permissionDefiners.size(); j++) {
                            final int targetAppId = permissionDefiners.valueAt(j);
                            if (targetAppId != newPkgSetting.getAppId()) {
                                mQueryableViaUsesPermission.add(newPkgSetting.getAppId(),
                                        targetAppId);
                            }
                        }
                    }
                    // Record in mUsesPermissionToUids that a permission was requested
                    // by a new package
                    if (!mUsesPermissionToUids.containsKey(usesPermissionName)) {
                        mUsesPermissionToUids.put(usesPermissionName, new ArraySet<>());
                    }
                    mUsesPermissionToUids.get(usesPermissionName).add(newPkgSetting.getAppId());
                }
            }
        }
        if (!newPkg.getPermissions().isEmpty()) {
            synchronized (mQueryableViaUsesPermissionLock) {
                // newPkg defines some permissions
                for (ParsedPermission permission : newPkg.getPermissions()) {
                    String permissionName = permission.getName();
                    // Lookup in the mUsesPermissionToUids cache if installed packages have
                    // requested this permission.
                    if (mUsesPermissionToUids.containsKey(permissionName)) {
                        final ArraySet<Integer> permissionUsers = mUsesPermissionToUids.get(
                                permissionName);
                        for (int j = 0; j < permissionUsers.size(); j++) {
                            final int queryingAppId = permissionUsers.valueAt(j);
                            if (queryingAppId != newPkgSetting.getAppId()) {
                                mQueryableViaUsesPermission.add(queryingAppId,
                                        newPkgSetting.getAppId());
                            }
                        }
                    }
                    // Record in mPermissionToUids that a permission was defined by a new package
                    if (!mPermissionToUids.containsKey(permissionName)) {
                        mPermissionToUids.put(permissionName, new ArraySet<>());
                    }
                    mPermissionToUids.get(permissionName).add(newPkgSetting.getAppId());
                }
            }
        }

        for (int i = existingSettings.size() - 1; i >= 0; i--) {
            final PackageStateInternal existingSetting = existingSettings.valueAt(i);
            if (existingSetting.getAppId() == newPkgSetting.getAppId()
                    || existingSetting.getPkg()
                    == null) {
                continue;
            }
            final AndroidPackage existingPkg = existingSetting.getPkg();
            // let's evaluate the ability of already added packages to see this new package
            if (!newIsForceQueryable) {
                if (!mQueriesViaComponentRequireRecompute.get()
                        && canQueryViaComponents(existingPkg, newPkg, mProtectedBroadcasts)) {
                    synchronized (mQueriesViaComponentLock) {
                        mQueriesViaComponent.add(existingSetting.getAppId(),
                                newPkgSetting.getAppId());
                    }
                }
                if (canQueryViaPackage(existingPkg, newPkg)
                        || canQueryAsInstaller(existingSetting, newPkg)
                        || canQueryAsUpdateOwner(existingSetting, newPkg)) {
                    synchronized (mQueriesViaPackageLock) {
                        mQueriesViaPackage.add(existingSetting.getAppId(),
                                newPkgSetting.getAppId());
                    }
                }
                if (canQueryViaUsesLibrary(existingPkg, newPkg)) {
                    synchronized (mQueryableViaUsesLibraryLock) {
                        mQueryableViaUsesLibrary.add(existingSetting.getAppId(),
                                newPkgSetting.getAppId());
                    }
                }
            }
            final boolean existingIsForceQueryable;
            synchronized (mForceQueryableLock) {
                existingIsForceQueryable = mForceQueryable.contains(existingSetting.getAppId());
            }
            // now we'll evaluate our new package's ability to see existing packages
            if (!existingIsForceQueryable) {
                if (!mQueriesViaComponentRequireRecompute.get()
                        && canQueryViaComponents(newPkg, existingPkg, mProtectedBroadcasts)) {
                    synchronized (mQueriesViaComponentLock) {
                        mQueriesViaComponent.add(newPkgSetting.getAppId(),
                                existingSetting.getAppId());
                    }
                }
                if (canQueryViaPackage(newPkg, existingPkg)
                        || canQueryAsInstaller(newPkgSetting, existingPkg)
                        || canQueryAsUpdateOwner(newPkgSetting, existingPkg)) {
                    synchronized (mQueriesViaPackageLock) {
                        mQueriesViaPackage.add(newPkgSetting.getAppId(),
                                existingSetting.getAppId());
                    }
                }
                if (canQueryViaUsesLibrary(newPkg, existingPkg)) {
                    synchronized (mQueryableViaUsesLibraryLock) {
                        mQueryableViaUsesLibrary.add(newPkgSetting.getAppId(),
                                existingSetting.getAppId());
                    }
                }
            }
            // if either package instruments the other, mark both as visible to one another
            if (newPkgSetting.getPkg() != null && existingSetting.getPkg() != null
                    && (pkgInstruments(newPkgSetting.getPkg(), existingSetting.getPkg())
                    || pkgInstruments(existingSetting.getPkg(), newPkgSetting.getPkg()))) {
                synchronized (mQueriesViaPackageLock) {
                    mQueriesViaPackage.add(newPkgSetting.getAppId(), existingSetting.getAppId());
                    mQueriesViaPackage.add(existingSetting.getAppId(), newPkgSetting.getAppId());
                }
            }
        }

        int existingSize = existingSettings.size();
        ArrayMap<String, AndroidPackage> existingPkgs = new ArrayMap<>(existingSize);
        for (int index = 0; index < existingSize; index++) {
            PackageStateInternal pkgSetting = existingSettings.valueAt(index);
            if (pkgSetting.getPkg() != null) {
                existingPkgs.put(pkgSetting.getPackageName(), pkgSetting.getPkg());
            }
        }

        ArraySet<String> changedPackages =
                mOverlayReferenceMapper.addPkg(newPkgSetting.getPkg(), existingPkgs);

        mFeatureConfig.updatePackageState(newPkgSetting, false /*removed*/);

        return changedPackages;
    }

    private void removeAppIdFromVisibilityCache(int appId) {
        synchronized (mCacheLock) {
            for (int i = 0; i < mShouldFilterCache.size(); i++) {
                if (UserHandle.getAppId(mShouldFilterCache.keyAt(i)) == appId) {
                    mShouldFilterCache.removeAt(i);
                    // The key was deleted so the list of keys has shifted left.  That means i
                    // is now pointing at the next key to be examined.  The decrement here and
                    // the loop increment together mean that i will be unchanged in the need
                    // iteration and will correctly point to the next key to be examined.
                    i--;
                }
            }
        }
    }

    private void updateEntireShouldFilterCache(Computer snapshot, int subjectUserId) {
        final ArrayMap<String, ? extends PackageStateInternal> settings =
                snapshot.getPackageStates();
        final UserInfo[] users = snapshot.getUserInfos();
        int userId = USER_NULL;
        for (int u = 0; u < users.length; u++) {
            if (subjectUserId == users[u].id) {
                userId = subjectUserId;
                break;
            }
        }
        if (userId == USER_NULL) {
            Slog.e(TAG, "We encountered a new user that isn't a member of known users, "
                    + "updating the whole cache");
            userId = USER_ALL;
        }
        updateEntireShouldFilterCacheInner(snapshot, settings, users, userId);

        onChanged();
    }

    private void updateEntireShouldFilterCacheInner(Computer snapshot,
            ArrayMap<String, ? extends PackageStateInternal> settings,
            UserInfo[] users,
            int subjectUserId) {
        synchronized (mCacheLock) {
            if (subjectUserId == USER_ALL) {
                mShouldFilterCache.clear();
            }
            mShouldFilterCache.setCapacity(users.length * settings.size());
            for (int i = settings.size() - 1; i >= 0; i--) {
                updateShouldFilterCacheForPackage(snapshot,
                        null /*skipPackage*/, settings.valueAt(i), settings, users,
                        subjectUserId, i);
            }
        }
    }

    private void updateEntireShouldFilterCacheAsync(PackageManagerInternal pmInternal, int reason) {
        updateEntireShouldFilterCacheAsync(pmInternal, CACHE_REBUILD_DELAY_MIN_MS, reason);
    }

    private void updateEntireShouldFilterCacheAsync(PackageManagerInternal pmInternal,
            long delayMs, int reason) {
        mHandler.postDelayed(() -> {
            if (!mCacheValid.compareAndSet(CACHE_INVALID, CACHE_VALID)) {
                // Cache is already valid.
                return;
            }

            final long currentTimeUs = SystemClock.currentTimeMicro();
            final ArrayMap<String, AndroidPackage> packagesCache = new ArrayMap<>();
            final UserInfo[][] usersRef = new UserInfo[1][];
            final Computer snapshot = (Computer) pmInternal.snapshot();
            final ArrayMap<String, ? extends PackageStateInternal> settings =
                    snapshot.getPackageStates();
            final UserInfo[] users = snapshot.getUserInfos();

            packagesCache.ensureCapacity(settings.size());
            usersRef[0] = users;
            // store away the references to the immutable packages, since settings are retained
            // during updates.
            for (int i = 0, max = settings.size(); i < max; i++) {
                final AndroidPackage pkg = settings.valueAt(i).getPkg();
                packagesCache.put(settings.keyAt(i), pkg);
            }

            updateEntireShouldFilterCacheInner(snapshot, settings, usersRef[0], USER_ALL);
            onChanged();
            logCacheRebuilt(reason, SystemClock.currentTimeMicro() - currentTimeUs,
                    users.length, settings.size());

            if (!mCacheValid.compareAndSet(CACHE_VALID, CACHE_VALID)) {
                Slog.i(TAG, "Cache invalidated while building, retrying.");
                updateEntireShouldFilterCacheAsync(pmInternal,
                        Math.min(delayMs * 2, CACHE_REBUILD_DELAY_MAX_MS), reason);
                return;
            }

            mCacheReady = true;
        }, delayMs);
    }

    public void onUserCreated(Computer snapshot, int newUserId) {
        if (!mCacheReady) {
            return;
        }
        final long currentTimeUs = SystemClock.currentTimeMicro();
        updateEntireShouldFilterCache(snapshot, newUserId);
        logCacheRebuilt(
                PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED__EVENT_TYPE__USER_CREATED,
                SystemClock.currentTimeMicro() - currentTimeUs,
                snapshot.getUserInfos().length,
                snapshot.getPackageStates().size());
    }

    public void onUserDeleted(Computer snapshot, @UserIdInt int userId) {
        if (!mCacheReady) {
            return;
        }
        final long currentTimeUs = SystemClock.currentTimeMicro();
        removeShouldFilterCacheForUser(userId);
        onChanged();
        logCacheRebuilt(
                PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED__EVENT_TYPE__USER_DELETED,
                SystemClock.currentTimeMicro() - currentTimeUs,
                snapshot.getUserInfos().length,
                snapshot.getPackageStates().size());
    }

    private void updateShouldFilterCacheForPackage(Computer snapshot,
            String packageName) {
        if (!mCacheReady) {
            return;
        }
        final ArrayMap<String, ? extends PackageStateInternal> settings =
                snapshot.getPackageStates();
        final UserInfo[] users = snapshot.getUserInfos();
        synchronized (mCacheLock) {
            updateShouldFilterCacheForPackage(snapshot, null /* skipPackage */,
                    settings.get(packageName), settings, users, USER_ALL,
                    settings.size() /*maxIndex*/);
        }
        onChanged();
    }

    @GuardedBy("mCacheLock")
    private void updateShouldFilterCacheForPackage(Computer snapshot,
            @Nullable String skipPackageName, PackageStateInternal subjectSetting, ArrayMap<String,
            ? extends PackageStateInternal> allSettings, UserInfo[] allUsers, int subjectUserId,
            int maxIndex) {
        for (int i = Math.min(maxIndex, allSettings.size() - 1); i >= 0; i--) {
            PackageStateInternal otherSetting = allSettings.valueAt(i);
            if (subjectSetting.getAppId() == otherSetting.getAppId()) {
                continue;
            }
            //noinspection StringEquality
            if (subjectSetting.getPackageName() == skipPackageName || otherSetting.getPackageName()
                    == skipPackageName) {
                continue;
            }
            if (subjectUserId == USER_ALL) {
                for (int su = 0; su < allUsers.length; su++) {
                    updateShouldFilterCacheForUser(snapshot, subjectSetting, allUsers, otherSetting,
                            allUsers[su].id);
                }
            } else {
                updateShouldFilterCacheForUser(snapshot, subjectSetting, allUsers, otherSetting,
                        subjectUserId);
            }
        }
    }

    @GuardedBy("mCacheLock")
    private void updateShouldFilterCacheForUser(Computer snapshot,
            PackageStateInternal subjectSetting, UserInfo[] allUsers,
            PackageStateInternal otherSetting, int subjectUserId) {
        for (int ou = 0; ou < allUsers.length; ou++) {
            int otherUser = allUsers[ou].id;
            int subjectUid = UserHandle.getUid(subjectUserId, subjectSetting.getAppId());
            int otherUid = UserHandle.getUid(otherUser, otherSetting.getAppId());
            final boolean shouldFilterSubjectToOther = shouldFilterApplicationInternal(snapshot,
                    subjectUid, subjectSetting, otherSetting, otherUser);
            final boolean shouldFilterOtherToSubject = shouldFilterApplicationInternal(snapshot,
                    otherUid, otherSetting, subjectSetting, subjectUserId);
            mShouldFilterCache.put(subjectUid, otherUid, shouldFilterSubjectToOther);
            mShouldFilterCache.put(otherUid, subjectUid, shouldFilterOtherToSubject);
        }
    }

    private void removeShouldFilterCacheForUser(int userId) {
        synchronized (mCacheLock) {
            // Sorted uids with the ascending order
            final int[] cacheUids = mShouldFilterCache.keys();
            final int size = cacheUids.length;
            int pos = Arrays.binarySearch(cacheUids, UserHandle.getUid(userId, 0));
            final int fromIndex = (pos >= 0 ? pos : ~pos);
            if (fromIndex >= size || UserHandle.getUserId(cacheUids[fromIndex]) != userId) {
                Slog.w(TAG, "Failed to remove should filter cache for user " + userId
                        + ", fromIndex=" + fromIndex);
                return;
            }
            pos = Arrays.binarySearch(cacheUids, UserHandle.getUid(userId + 1, 0) - 1);
            final int toIndex = (pos >= 0 ? pos + 1 : ~pos);
            if (fromIndex >= toIndex || UserHandle.getUserId(cacheUids[toIndex - 1]) != userId) {
                Slog.w(TAG, "Failed to remove should filter cache for user " + userId
                        + ", fromIndex=" + fromIndex + ", toIndex=" + toIndex);
                return;
            }
            mShouldFilterCache.removeRange(fromIndex, toIndex);
            mShouldFilterCache.compact();
        }
    }

    private static boolean isSystemSigned(@NonNull SigningDetails sysSigningDetails,
            PackageStateInternal pkgSetting) {
        return pkgSetting.isSystem()
                && pkgSetting.getSigningDetails().signaturesMatchExactly(sysSigningDetails);
    }

    private void collectProtectedBroadcasts(
            ArrayMap<String, ? extends PackageStateInternal> existingSettings,
            @Nullable String excludePackage) {
        synchronized (mProtectedBroadcastsLock) {
            mProtectedBroadcasts.clear();
            for (int i = existingSettings.size() - 1; i >= 0; i--) {
                PackageStateInternal setting = existingSettings.valueAt(i);
                if (setting.getPkg() == null || setting.getPkg().getPackageName().equals(
                        excludePackage)) {
                    continue;
                }
                final List<String> protectedBroadcasts = setting.getPkg().getProtectedBroadcasts();
                if (!protectedBroadcasts.isEmpty()) {
                    mProtectedBroadcasts.addAll(protectedBroadcasts);
                }
            }
        }
    }

    @Override
    protected boolean isQueryableViaComponentWhenRequireRecompute(
            ArrayMap<String, ? extends PackageStateInternal> existingSettings,
            PackageStateInternal callingPkgSetting,
            ArraySet<PackageStateInternal> callingSharedPkgSettings,
            AndroidPackage targetPkg,
            int callingAppId, int targetAppId) {
        // Recompute the whole mQueriesViaComponent because mProtectedBroadcasts have changed
        recomputeComponentVisibility(existingSettings);
        return isQueryableViaComponent(callingAppId, targetAppId);
    }

    /**
     * This method recomputes all component / intent-based visibility and is intended to match the
     * relevant logic of {@link #addPackageInternal(PackageStateInternal, ArrayMap)}
     */
    private void recomputeComponentVisibility(
            ArrayMap<String, ? extends PackageStateInternal> existingSettings) {
        final WatchedArraySet<String> protectedBroadcasts;
        synchronized (mProtectedBroadcastsLock) {
            protectedBroadcasts = mProtectedBroadcasts.snapshot();
        }
        final ParallelComputeComponentVisibility computer = new ParallelComputeComponentVisibility(
                existingSettings, mForceQueryable, protectedBroadcasts);
        synchronized (mQueriesViaComponentLock) {
            mQueriesViaComponent.clear();
            computer.execute(mQueriesViaComponent);
        }

        mQueriesViaComponentRequireRecompute.set(false);
        onChanged();
    }

    /**
     * Equivalent to calling {@link #addPackage(Computer, PackageStateInternal, boolean, boolean)}
     * with {@code isReplace} and {@code retainImplicitGrantOnReplace} equal to {@code false}.
     *
     * @see AppsFilterImpl#addPackage(Computer, PackageStateInternal, boolean, boolean)
     */
    public void addPackage(Computer snapshot, PackageStateInternal newPkgSetting) {
        addPackage(snapshot, newPkgSetting, false /* isReplace */,
                false /* retainImplicitGrantOnReplace */);
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param setting the setting of the package being removed.
     */
    public void removePackage(Computer snapshot, PackageStateInternal setting) {
        final long currentTimeUs = SystemClock.currentTimeMicro();
        removePackageInternal(snapshot, setting,
                false /* isReplace */, false /* retainImplicitGrantOnReplace */);
        logCacheUpdated(
                PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED__EVENT_TYPE__PACKAGE_DELETED,
                SystemClock.currentTimeMicro() - currentTimeUs,
                snapshot.getUserInfos().length,
                snapshot.getPackageStates().size(),
                setting.getAppId());
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param setting   the setting of the package being removed.
     * @param isReplace if the package is being replaced.
     * @param retainImplicitGrantOnReplace {@code true} to retain implicit grant access if
     *                                     the package is being replaced.
     */
    private void removePackageInternal(Computer snapshot, PackageStateInternal setting,
            boolean isReplace, boolean retainImplicitGrantOnReplace) {
        final ArraySet<String> additionalChangedPackages;
        final ArrayMap<String, ? extends PackageStateInternal> settings =
                snapshot.getPackageStates();
        final UserInfo[] users = snapshot.getUserInfos();
        final int userCount = users.length;
        if (!isReplace || !retainImplicitGrantOnReplace) {
            synchronized (mImplicitlyQueryableLock) {
                for (int u = 0; u < userCount; u++) {
                    final int userId = users[u].id;
                    final int removingUid = UserHandle.getUid(userId, setting.getAppId());
                    mImplicitlyQueryable.remove(removingUid);
                    for (int i = mImplicitlyQueryable.size() - 1; i >= 0; i--) {
                        mImplicitlyQueryable.remove(mImplicitlyQueryable.keyAt(i),
                                removingUid);
                    }

                    if (isReplace) {
                        continue;
                    }

                    mRetainedImplicitlyQueryable.remove(removingUid);
                    for (int i = mRetainedImplicitlyQueryable.size() - 1; i >= 0; i--) {
                        mRetainedImplicitlyQueryable.remove(
                                mRetainedImplicitlyQueryable.keyAt(i), removingUid);
                    }
                }
            }
        }

        if (!mQueriesViaComponentRequireRecompute.get()) {
            synchronized (mQueriesViaComponentLock) {
                mQueriesViaComponent.remove(setting.getAppId());
                for (int i = mQueriesViaComponent.size() - 1; i >= 0; i--) {
                    mQueriesViaComponent.remove(mQueriesViaComponent.keyAt(i), setting.getAppId());
                }
            }
        }

        synchronized (mQueriesViaPackageLock) {
            mQueriesViaPackage.remove(setting.getAppId());
            for (int i = mQueriesViaPackage.size() - 1; i >= 0; i--) {
                mQueriesViaPackage.remove(mQueriesViaPackage.keyAt(i),
                        setting.getAppId());
            }
        }

        synchronized (mQueryableViaUsesLibraryLock) {
            mQueryableViaUsesLibrary.remove(setting.getAppId());
            for (int i = mQueryableViaUsesLibrary.size() - 1; i >= 0; i--) {
                mQueryableViaUsesLibrary.remove(mQueryableViaUsesLibrary.keyAt(i),
                        setting.getAppId());
            }
        }

        synchronized (mQueryableViaUsesPermissionLock) {
            if (setting.getPkg() != null && !setting.getPkg().getPermissions().isEmpty()) {
                for (ParsedPermission permission : setting.getPkg().getPermissions()) {
                    String permissionName = permission.getName();
                    if (mPermissionToUids.containsKey(permissionName)) {
                        mPermissionToUids.get(permissionName).remove(setting.getAppId());
                        if (mPermissionToUids.get(permissionName).isEmpty()) {
                            mPermissionToUids.remove(permissionName);
                        }
                    }
                }
            }
            if (setting.getPkg() != null && !setting.getPkg().getUsesPermissions().isEmpty()) {
                for (ParsedUsesPermission usesPermission : setting.getPkg().getUsesPermissions()) {
                    String usesPermissionName = usesPermission.getName();
                    if (mUsesPermissionToUids.containsKey(usesPermissionName)) {
                        mUsesPermissionToUids.get(usesPermissionName).remove(setting.getAppId());
                        if (mUsesPermissionToUids.get(usesPermissionName).isEmpty()) {
                            mUsesPermissionToUids.remove(usesPermissionName);
                        }
                    }
                }
            }
            mQueryableViaUsesPermission.remove(setting.getAppId());
        }

        synchronized (mForceQueryableLock) {
            mForceQueryable.remove(setting.getAppId());
        }

        boolean protectedBroadcastsChanged = false;
        synchronized (mProtectedBroadcastsLock) {
            if (setting.getPkg() != null
                    && !setting.getPkg().getProtectedBroadcasts().isEmpty()) {
                final String removingPackageName = setting.getPkg().getPackageName();
                final ArrayList<String> protectedBroadcasts = new ArrayList<>(
                        mProtectedBroadcasts.untrackedStorage());
                collectProtectedBroadcasts(settings, removingPackageName);
                for (int i = 0; i < protectedBroadcasts.size(); ++i) {
                    if (!mProtectedBroadcasts.contains(protectedBroadcasts.get(i))) {
                        protectedBroadcastsChanged = true;
                        break;
                    }
                }
            }
        }

        if (protectedBroadcastsChanged) {
            mQueriesViaComponentRequireRecompute.set(true);
        }

        additionalChangedPackages = mOverlayReferenceMapper.removePkg(setting.getPackageName());
        mFeatureConfig.updatePackageState(setting, true /*removed*/);

        // After removing all traces of the package, if it's part of a shared user, re-add other
        // shared user members to re-establish visibility between them and other packages.
        // NOTE: this must come after all removals from data structures but before we update the
        // cache
        final SharedUserApi sharedUserApi = setting.hasSharedUser()
                ? snapshot.getSharedUser(setting.getSharedUserAppId()) : null;
        if (sharedUserApi != null) {
            final ArraySet<? extends PackageStateInternal> sharedUserPackages =
                    sharedUserApi.getPackageStates();
            for (int i = sharedUserPackages.size() - 1; i >= 0; i--) {
                if (sharedUserPackages.valueAt(i) == setting) {
                    continue;
                }
                addPackageInternal(
                        sharedUserPackages.valueAt(i), settings);
            }
        }

        if (mCacheReady) {
            removeAppIdFromVisibilityCache(setting.getAppId());

            if (sharedUserApi != null) {
                final ArraySet<? extends PackageStateInternal> sharedUserPackages =
                        sharedUserApi.getPackageStates();
                for (int i = sharedUserPackages.size() - 1; i >= 0; i--) {
                    PackageStateInternal siblingSetting =
                            sharedUserPackages.valueAt(i);
                    if (siblingSetting == setting) {
                        continue;
                    }
                    synchronized (mCacheLock) {
                        updateShouldFilterCacheForPackage(snapshot,
                                setting.getPackageName(), siblingSetting, settings,
                                users, USER_ALL, settings.size());
                    }
                }
            }

            if (additionalChangedPackages != null) {
                for (int index = 0; index < additionalChangedPackages.size(); index++) {
                    String changedPackage = additionalChangedPackages.valueAt(index);
                    PackageStateInternal changedPkgSetting = settings.get(changedPackage);
                    if (changedPkgSetting == null) {
                        // It's possible for the overlay mapper to know that an actor
                        // package changed via an explicit reference, even if the actor
                        // isn't installed, so skip if that's the case.
                        continue;
                    }
                    synchronized (mCacheLock) {
                        updateShouldFilterCacheForPackage(snapshot, null, changedPkgSetting,
                                settings, users, USER_ALL, settings.size());
                    }
                }
            }
        } else {
            invalidateCache("removePackage: " + setting.getPackageName());
        }
        onChanged();
    }

    /** Returns {@code true} if the source package instruments the target package. */
    private static boolean pkgInstruments(
            @NonNull AndroidPackage source, @NonNull AndroidPackage target) {
        try {
            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "pkgInstruments");
            }
            final String packageName = target.getPackageName();
            final List<ParsedInstrumentation> inst = source.getInstrumentations();
            for (int i = ArrayUtils.size(inst) - 1; i >= 0; i--) {
                if (Objects.equals(inst.get(i).getTargetPackage(), packageName)) {
                    return true;
                }
            }
            return false;
        } finally {
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }
    }

    private void logCacheRebuilt(int eventId, long latency, int userCount, int packageCount) {
        FrameworkStatsLog.write(PACKAGE_MANAGER_APPS_FILTER_CACHE_BUILD_REPORTED,
                eventId, latency, userCount, packageCount, mShouldFilterCache.size());
    }

    private void logCacheUpdated(int eventId, long latency, int userCount, int packageCount,
            int appId) {
        if (!mCacheReady) {
            return;
        }
        FrameworkStatsLog.write(PACKAGE_MANAGER_APPS_FILTER_CACHE_UPDATE_REPORTED,
                eventId, appId, latency, userCount, packageCount, mShouldFilterCache.size());
    }
}
