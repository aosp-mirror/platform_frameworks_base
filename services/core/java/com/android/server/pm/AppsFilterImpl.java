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
import static com.android.server.pm.AppsFilterUtils.canQueryAsInstaller;
import static com.android.server.pm.AppsFilterUtils.canQueryViaComponents;
import static com.android.server.pm.AppsFilterUtils.canQueryViaPackage;
import static com.android.server.pm.AppsFilterUtils.canQueryViaUsesLibrary;
import static com.android.server.pm.AppsFilterUtils.requestsQueryAllPackages;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.content.pm.UserInfo;
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
import com.android.server.FgThread;
import com.android.server.compat.CompatChange;
import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.WatchedArrayList;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.WatchedSparseBooleanMatrix;
import com.android.server.utils.WatchedSparseSetArray;
import com.android.server.utils.Watcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

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
    private SnapshotCache<AppsFilterSnapshot> mSnapshot;

    /**
     * Watchable machinery
     */
    private final WatchableImpl mWatchable = new WatchableImpl();

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

    @VisibleForTesting(visibility = PRIVATE)
    AppsFilterImpl(FeatureConfig featureConfig,
            String[] forceQueryableList,
            boolean systemAppsQueryable,
            @Nullable OverlayReferenceMapper.Provider overlayProvider,
            Executor backgroundExecutor) {
        mFeatureConfig = featureConfig;
        mForceQueryableByDevicePackageNames = forceQueryableList;
        mSystemAppsQueryable = systemAppsQueryable;
        mOverlayReferenceMapper = new OverlayReferenceMapper(true /*deferRebuild*/,
                overlayProvider);
        mBackgroundExecutor = backgroundExecutor;
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
        mForceQueryable = new WatchedArraySet<>();
        mForceQueryableSnapshot = new SnapshotCache.Auto<>(
                mForceQueryable, mForceQueryable, "AppsFilter.mForceQueryable");
        mProtectedBroadcasts = new WatchedArrayList<>();
        mProtectedBroadcastsSnapshot = new SnapshotCache.Auto<>(
                mProtectedBroadcasts, mProtectedBroadcasts, "AppsFilter.mProtectedBroadcasts");

        mSnapshot = new SnapshotCache<AppsFilterSnapshot>(this, this) {
            @Override
            public AppsFilterSnapshot createSnapshot() {
                return new AppsFilterSnapshotImpl(AppsFilterImpl.this);
            }
        };
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
            PackageDataSnapshot snapshot = mPmInternal.snapshot();
            AndroidPackage pkg = snapshot.getPackage(packageName);
            if (pkg == null) {
                return;
            }
            updateEnabledState(pkg);
            mAppsFilter.updateShouldFilterCacheForPackage(snapshot, packageName);
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
                injector.getBackgroundExecutor());
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
        synchronized (mLock) {
            changed = retainOnUpdate
                    ? mRetainedImplicitlyQueryable.add(recipientUid, visibleUid)
                    : mImplicitlyQueryable.add(recipientUid, visibleUid);
        }
        if (changed && DEBUG_LOGGING) {
            Slog.i(TAG, (retainOnUpdate ? "retained " : "") + "implicit access granted: "
                    + recipientUid + " -> " + visibleUid);
        }

        if (mSystemReady) {
            synchronized (mCacheLock) {
                // update the cache in a one-off manner since we've got all the information we
                // need.
                mShouldFilterCache.put(recipientUid, visibleUid, false);
            }
        }
        onChanged();
        return changed;
    }

    public void onSystemReady(PackageManagerInternal pmInternal) {
        mOverlayReferenceMapper.rebuildIfDeferred();
        mFeatureConfig.onSystemReady();

        updateEntireShouldFilterCacheAsync(pmInternal);
        mSystemReady = true;
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkgSetting the new setting being added
     * @param isReplace     if the package is being replaced and may need extra cleanup.
     */
    public void addPackage(PackageDataSnapshot snapshot, PackageStateInternal newPkgSetting,
            boolean isReplace) {
        if (DEBUG_TRACING) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "filter.addPackage");
        }
        try {
            if (isReplace) {
                // let's first remove any prior rules for this package
                removePackage(snapshot, newPkgSetting, true /*isReplace*/);
            }
            final ArrayMap<String, ? extends PackageStateInternal> settings =
                    snapshot.getPackageStates();
            final UserInfo[] users = snapshot.getUserInfos();
            final ArraySet<String> additionalChangedPackages =
                    addPackageInternal(newPkgSetting, settings);
            if (mSystemReady) {
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
            } // else, rebuild entire cache when system is ready
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
                    synchronized (mLock) {
                        mForceQueryable.add(setting.getAppId());
                    }
                }
            }
        }

        final AndroidPackage newPkg = newPkgSetting.getPkg();
        if (newPkg == null) {
            return null;
        }

        synchronized (mLock) {
            if (mProtectedBroadcasts.addAll(newPkg.getProtectedBroadcasts())) {
                mQueriesViaComponentRequireRecompute = true;
            }

            final boolean newIsForceQueryable =
                    mForceQueryable.contains(newPkgSetting.getAppId())
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
                    if (!mQueriesViaComponentRequireRecompute && canQueryViaComponents(existingPkg,
                            newPkg, mProtectedBroadcasts)) {
                        mQueriesViaComponent.add(existingSetting.getAppId(),
                                newPkgSetting.getAppId());
                    }
                    if (canQueryViaPackage(existingPkg, newPkg)
                            || canQueryAsInstaller(existingSetting, newPkg)) {
                        mQueriesViaPackage.add(existingSetting.getAppId(),
                                newPkgSetting.getAppId());
                    }
                    if (canQueryViaUsesLibrary(existingPkg, newPkg)) {
                        mQueryableViaUsesLibrary.add(existingSetting.getAppId(),
                                newPkgSetting.getAppId());
                    }
                }
                // now we'll evaluate our new package's ability to see existing packages
                if (!mForceQueryable.contains(existingSetting.getAppId())) {
                    if (!mQueriesViaComponentRequireRecompute && canQueryViaComponents(newPkg,
                            existingPkg, mProtectedBroadcasts)) {
                        mQueriesViaComponent.add(newPkgSetting.getAppId(),
                                existingSetting.getAppId());
                    }
                    if (canQueryViaPackage(newPkg, existingPkg)
                            || canQueryAsInstaller(newPkgSetting, existingPkg)) {
                        mQueriesViaPackage.add(newPkgSetting.getAppId(),
                                existingSetting.getAppId());
                    }
                    if (canQueryViaUsesLibrary(newPkg, existingPkg)) {
                        mQueryableViaUsesLibrary.add(newPkgSetting.getAppId(),
                                existingSetting.getAppId());
                    }
                }
                // if either package instruments the other, mark both as visible to one another
                if (newPkgSetting.getPkg() != null && existingSetting.getPkg() != null
                        && (pkgInstruments(newPkgSetting.getPkg(), existingSetting.getPkg())
                        || pkgInstruments(existingSetting.getPkg(), newPkgSetting.getPkg()))) {
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
        if (!mSystemReady) {
            return;
        }
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

    private void updateEntireShouldFilterCache(PackageDataSnapshot snapshot, int subjectUserId) {
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

    private void updateEntireShouldFilterCacheInner(PackageDataSnapshot snapshot,
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

    private void updateEntireShouldFilterCacheAsync(PackageManagerInternal pmInternal) {
        mBackgroundExecutor.execute(() -> {
            final ArrayMap<String, AndroidPackage> packagesCache = new ArrayMap<>();
            final UserInfo[][] usersRef = new UserInfo[1][];
            final PackageDataSnapshot snapshot = pmInternal.snapshot();
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
        });
    }

    public void onUserCreated(PackageDataSnapshot snapshot, int newUserId) {
        if (!mSystemReady) {
            return;
        }
        updateEntireShouldFilterCache(snapshot, newUserId);
    }

    public void onUserDeleted(@UserIdInt int userId) {
        if (!mSystemReady) {
            return;
        }
        removeShouldFilterCacheForUser(userId);
        onChanged();
    }

    private void updateShouldFilterCacheForPackage(PackageDataSnapshot snapshot,
            String packageName) {
        if (!mSystemReady) {
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
    private void updateShouldFilterCacheForPackage(PackageDataSnapshot snapshot,
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
    private void updateShouldFilterCacheForUser(PackageDataSnapshot snapshot,
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
        synchronized (mLock) {
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
        synchronized (mLock) {
            mQueriesViaComponent.clear();
            for (int i = existingSettings.size() - 1; i >= 0; i--) {
                PackageStateInternal setting = existingSettings.valueAt(i);
                if (setting.getPkg() == null || requestsQueryAllPackages(setting.getPkg())) {
                    continue;
                }
                for (int j = existingSettings.size() - 1; j >= 0; j--) {
                    if (i == j) {
                        continue;
                    }
                    final PackageStateInternal otherSetting = existingSettings.valueAt(j);
                    if (otherSetting.getPkg() == null || mForceQueryable.contains(
                            otherSetting.getAppId())) {
                        continue;
                    }
                    if (canQueryViaComponents(setting.getPkg(), otherSetting.getPkg(),
                            mProtectedBroadcasts)) {
                        mQueriesViaComponent.add(setting.getAppId(), otherSetting.getAppId());
                    }
                }
            }
        }
        mQueriesViaComponentRequireRecompute = false;
        onChanged();
    }

    /**
     * Equivalent to calling {@link #addPackage(PackageDataSnapshot, PackageStateInternal, boolean)}
     * with {@code isReplace} equal to {@code false}.
     *
     * @see AppsFilterImpl#addPackage(PackageDataSnapshot, PackageStateInternal, boolean)
     */
    public void addPackage(PackageDataSnapshot snapshot, PackageStateInternal newPkgSetting) {
        addPackage(snapshot, newPkgSetting, false /* isReplace */);
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param setting   the setting of the package being removed.
     * @param isReplace if the package is being replaced.
     */
    public void removePackage(PackageDataSnapshot snapshot, PackageStateInternal setting,
            boolean isReplace) {
        final ArraySet<String> additionalChangedPackages;
        final ArrayMap<String, ? extends PackageStateInternal> settings =
                snapshot.getPackageStates();
        final UserInfo[] users = snapshot.getUserInfos();
        final Collection<SharedUserSetting> sharedUserSettings = snapshot.getAllSharedUsers();
        final int userCount = users.length;
        synchronized (mLock) {
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

            if (!mQueriesViaComponentRequireRecompute) {
                mQueriesViaComponent.remove(setting.getAppId());
                for (int i = mQueriesViaComponent.size() - 1; i >= 0; i--) {
                    mQueriesViaComponent.remove(mQueriesViaComponent.keyAt(i),
                            setting.getAppId());
                }
            }
            mQueriesViaPackage.remove(setting.getAppId());
            for (int i = mQueriesViaPackage.size() - 1; i >= 0; i--) {
                mQueriesViaPackage.remove(mQueriesViaPackage.keyAt(i),
                        setting.getAppId());
            }
            mQueryableViaUsesLibrary.remove(setting.getAppId());
            for (int i = mQueryableViaUsesLibrary.size() - 1; i >= 0; i--) {
                mQueryableViaUsesLibrary.remove(mQueryableViaUsesLibrary.keyAt(i),
                        setting.getAppId());
            }

            mForceQueryable.remove(setting.getAppId());

            if (setting.getPkg() != null
                    && !setting.getPkg().getProtectedBroadcasts().isEmpty()) {
                final String removingPackageName = setting.getPkg().getPackageName();
                final ArrayList<String> protectedBroadcasts = new ArrayList<>();
                protectedBroadcasts.addAll(mProtectedBroadcasts.untrackedStorage());
                collectProtectedBroadcasts(settings, removingPackageName);
                if (!mProtectedBroadcasts.containsAll(protectedBroadcasts)) {
                    mQueriesViaComponentRequireRecompute = true;
                }
            }
        }

        additionalChangedPackages = mOverlayReferenceMapper.removePkg(setting.getPackageName());
        mFeatureConfig.updatePackageState(setting, true /*removed*/);

        // After removing all traces of the package, if it's part of a shared user, re-add other
        // shared user members to re-establish visibility between them and other packages.
        // NOTE: this must come after all removals from data structures but before we update the
        // cache
        if (setting.hasSharedUser()) {
            final ArraySet<? extends PackageStateInternal> sharedUserPackages =
                    getSharedUserPackages(setting.getSharedUserAppId(), sharedUserSettings);
            for (int i = sharedUserPackages.size() - 1; i >= 0; i--) {
                if (sharedUserPackages.valueAt(i) == setting) {
                    continue;
                }
                addPackageInternal(
                        sharedUserPackages.valueAt(i), settings);
            }
        }

        removeAppIdFromVisibilityCache(setting.getAppId());
        if (mSystemReady && setting.hasSharedUser()) {
            final ArraySet<? extends PackageStateInternal> sharedUserPackages =
                    getSharedUserPackages(setting.getSharedUserAppId(), sharedUserSettings);
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

        if (mSystemReady) {
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
}
