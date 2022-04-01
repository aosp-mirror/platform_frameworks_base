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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SigningDetails;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.function.QuadFunction;
import com.android.server.FgThread;
import com.android.server.compat.CompatChange;
import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.parsing.pkg.AndroidPackageUtils;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.component.ParsedComponent;
import com.android.server.pm.pkg.component.ParsedInstrumentation;
import com.android.server.pm.pkg.component.ParsedIntentInfo;
import com.android.server.pm.pkg.component.ParsedMainComponent;
import com.android.server.pm.pkg.component.ParsedProvider;
import com.android.server.utils.Snappable;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watchable;
import com.android.server.utils.WatchableImpl;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayList;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.WatchedSparseBooleanMatrix;
import com.android.server.utils.WatchedSparseSetArray;
import com.android.server.utils.Watcher;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.Executor;

/**
 * The entity responsible for filtering visibility between apps based on declarations in their
 * manifests.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class AppsFilterImpl implements AppsFilterSnapshot, Watchable, Snappable {

    private static final String TAG = "AppsFilter";

    // Logs all filtering instead of enforcing
    private static final boolean DEBUG_ALLOW_ALL = false;
    private static final boolean DEBUG_LOGGING = false;
    private static final boolean DEBUG_TRACING = false;

    /**
     * This contains a list of app UIDs that are implicitly queryable because another app explicitly
     * interacted with it. For example, if application A starts a service in application B,
     * application B is implicitly allowed to query for application A; regardless of any manifest
     * entries.
     */
    @GuardedBy("mLock")
    @Watched
    private final WatchedSparseSetArray<Integer> mImplicitlyQueryable;
    private final SnapshotCache<WatchedSparseSetArray<Integer>> mImplicitQueryableSnapshot;

    /**
     * This contains a list of app UIDs that are implicitly queryable because another app explicitly
     * interacted with it, but could keep across package updates. For example, if application A
     * grants persistable uri permission to application B; regardless of any manifest entries.
     */
    @GuardedBy("mLock")
    @Watched
    private final WatchedSparseSetArray<Integer> mRetainedImplicitlyQueryable;
    private final SnapshotCache<WatchedSparseSetArray<Integer>>
            mRetainedImplicitlyQueryableSnapshot;

    /**
     * A mapping from the set of App IDs that query other App IDs via package name to the
     * list of packages that they can see.
     */
    @GuardedBy("mLock")
    @Watched
    private final WatchedSparseSetArray<Integer> mQueriesViaPackage;
    private final SnapshotCache<WatchedSparseSetArray<Integer>> mQueriesViaPackageSnapshot;

    /**
     * A mapping from the set of App IDs that query others via component match to the list
     * of packages that the they resolve to.
     */
    @GuardedBy("mLock")
    @Watched
    private final WatchedSparseSetArray<Integer> mQueriesViaComponent;
    private final SnapshotCache<WatchedSparseSetArray<Integer>> mQueriesViaComponentSnapshot;

    /**
     * A mapping from the set of App IDs that query other App IDs via library name to the
     * list of packages that they can see.
     */
    @GuardedBy("mLock")
    @Watched
    private final WatchedSparseSetArray<Integer> mQueryableViaUsesLibrary;
    private final SnapshotCache<WatchedSparseSetArray<Integer>> mQueryableViaUsesLibrarySnapshot;

    /**
     * Executor for running reasonably short background tasks such as building the initial
     * visibility cache.
     */
    private final Executor mBackgroundExecutor;

    /**
     * Pending full recompute of mQueriesViaComponent. Occurs when a package adds a new set of
     * protected broadcast. This in turn invalidates all prior additions and require a very
     * computationally expensive recomputing.
     * Full recompute is done lazily at the point when we use mQueriesViaComponent to filter apps.
     */
    private boolean mQueriesViaComponentRequireRecompute = false;

    /**
     * A set of App IDs that are always queryable by any package, regardless of their manifest
     * content.
     */
    @Watched
    @GuardedBy("mLock")
    private final WatchedArraySet<Integer> mForceQueryable;
    private final SnapshotCache<WatchedArraySet<Integer>> mForceQueryableSnapshot;

    /**
     * The set of package names provided by the device that should be force queryable regardless of
     * their manifest contents.
     */
    private final String[] mForceQueryableByDevicePackageNames;

    /** True if all system apps should be made queryable by default. */
    private final boolean mSystemAppsQueryable;
    private final FeatureConfig mFeatureConfig;
    private final OverlayReferenceMapper mOverlayReferenceMapper;
    private final StateProvider mStateProvider;
    private final PackageManagerInternal mPmInternal;
    private SigningDetails mSystemSigningDetails;

    @GuardedBy("mLock")
    @Watched
    private final WatchedArrayList<String> mProtectedBroadcasts;
    private final SnapshotCache<WatchedArrayList<String>> mProtectedBroadcastsSnapshot;

    private final Object mCacheLock = new Object();

    /**
     * This structure maps uid -> uid and indicates whether access from the first should be
     * filtered to the second. It's essentially a cache of the
     * {@link #shouldFilterApplicationInternal(int, Object, PackageStateInternal, int)} call.
     * NOTE: It can only be relied upon after the system is ready to avoid unnecessary update on
     * initial scam and is empty until {@link #onSystemReady()} is called.
     */
    @GuardedBy("mCacheLock")
    @NonNull
    private final WatchedSparseBooleanMatrix mShouldFilterCache;
    private final SnapshotCache<WatchedSparseBooleanMatrix> mShouldFilterCacheSnapshot;

    /**
     * Guards the accesses for the list/set fields except for {@link #mShouldFilterCache}
     */
    private final Object mLock = new Object();

    /**
     * A cached snapshot.
     */
    private final SnapshotCache<AppsFilterImpl> mSnapshot;

    private SnapshotCache<AppsFilterImpl> makeCache() {
        return new SnapshotCache<AppsFilterImpl>(this, this) {
            @Override
            public AppsFilterImpl createSnapshot() {
                AppsFilterImpl s = new AppsFilterImpl(mSource);
                s.mWatchable.seal();
                return s;
            }
        };
    }

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
            AppsFilterImpl.this.dispatchChange(what);
        }
    };

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
    AppsFilterImpl(StateProvider stateProvider,
            FeatureConfig featureConfig,
            String[] forceQueryableList,
            boolean systemAppsQueryable,
            @Nullable OverlayReferenceMapper.Provider overlayProvider,
            Executor backgroundExecutor,
            PackageManagerInternal pmInternal) {
        mFeatureConfig = featureConfig;
        mForceQueryableByDevicePackageNames = forceQueryableList;
        mSystemAppsQueryable = systemAppsQueryable;
        mOverlayReferenceMapper = new OverlayReferenceMapper(true /*deferRebuild*/,
                overlayProvider);
        mStateProvider = stateProvider;
        mPmInternal = pmInternal;
        mBackgroundExecutor = backgroundExecutor;
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
        mShouldFilterCache = new WatchedSparseBooleanMatrix();
        mShouldFilterCacheSnapshot = new SnapshotCache.Auto<>(
                mShouldFilterCache, mShouldFilterCache, "AppsFilter.mShouldFilterCache");

        registerObservers();
        Watchable.verifyWatchedAttributes(this, mObserver);
        mSnapshot = makeCache();
    }

    /**
     * The copy constructor is used by PackageManagerService to construct a snapshot.
     */
    private AppsFilterImpl(AppsFilterImpl orig) {
        synchronized (orig.mLock) {
            mImplicitlyQueryable = orig.mImplicitQueryableSnapshot.snapshot();
            mImplicitQueryableSnapshot = new SnapshotCache.Sealed<>();
            mRetainedImplicitlyQueryable = orig.mRetainedImplicitlyQueryableSnapshot.snapshot();
            mRetainedImplicitlyQueryableSnapshot = new SnapshotCache.Sealed<>();
            mQueriesViaPackage = orig.mQueriesViaPackageSnapshot.snapshot();
            mQueriesViaPackageSnapshot = new SnapshotCache.Sealed<>();
            mQueriesViaComponent = orig.mQueriesViaComponentSnapshot.snapshot();
            mQueriesViaComponentSnapshot = new SnapshotCache.Sealed<>();
            mQueryableViaUsesLibrary = orig.mQueryableViaUsesLibrarySnapshot.snapshot();
            mQueryableViaUsesLibrarySnapshot = new SnapshotCache.Sealed<>();
            mForceQueryable = orig.mForceQueryableSnapshot.snapshot();
            mForceQueryableSnapshot = new SnapshotCache.Sealed<>();
            mProtectedBroadcasts = orig.mProtectedBroadcastsSnapshot.snapshot();
            mProtectedBroadcastsSnapshot = new SnapshotCache.Sealed<>();
        }
        mQueriesViaComponentRequireRecompute = orig.mQueriesViaComponentRequireRecompute;
        mForceQueryableByDevicePackageNames =
                Arrays.copyOf(orig.mForceQueryableByDevicePackageNames,
                        orig.mForceQueryableByDevicePackageNames.length);
        mSystemAppsQueryable = orig.mSystemAppsQueryable;
        mFeatureConfig = orig.mFeatureConfig;
        mOverlayReferenceMapper = orig.mOverlayReferenceMapper;
        mStateProvider = orig.mStateProvider;
        mSystemSigningDetails = orig.mSystemSigningDetails;
        synchronized (orig.mCacheLock) {
            mShouldFilterCache = orig.mShouldFilterCacheSnapshot.snapshot();
            mShouldFilterCacheSnapshot = new SnapshotCache.Sealed<>();
        }

        mBackgroundExecutor = null;
        mPmInternal = null;
        mSnapshot = new SnapshotCache.Sealed<>();
    }

    @SuppressWarnings("GuardedBy")
    private void registerObservers() {
        mImplicitlyQueryable.registerObserver(mObserver);
        mRetainedImplicitlyQueryable.registerObserver(mObserver);
        mQueriesViaPackage.registerObserver(mObserver);
        mQueriesViaComponent.registerObserver(mObserver);
        mQueryableViaUsesLibrary.registerObserver(mObserver);
        mForceQueryable.registerObserver(mObserver);
        mProtectedBroadcasts.registerObserver(mObserver);
        mShouldFilterCache.registerObserver(mObserver);
    }

    /**
     * Return a snapshot.  If the cached snapshot is null, build a new one.  The logic in
     * the function ensures that this function returns a valid snapshot even if a race
     * condition causes the cached snapshot to be cleared asynchronously to this method.
     */
    public AppsFilterSnapshot snapshot() {
        return mSnapshot.snapshot();
    }

    /**
     * Provides system state to AppsFilter via {@link CurrentStateCallback} after properly guarding
     * the data with the package lock.
     *
     * Don't call {@link #runWithState} with {@link #mCacheLock} held.
     */
    @VisibleForTesting(visibility = PRIVATE)
    public interface StateProvider {
        void runWithState(CurrentStateCallback callback);

        interface CurrentStateCallback {
            void currentState(ArrayMap<String, ? extends PackageStateInternal> settings,
                    UserInfo[] users);
        }
    }

    @VisibleForTesting(visibility = PRIVATE)
    public interface FeatureConfig {

        /** Called when the system is ready and components can be queried. */
        void onSystemReady();

        /** @return true if we should filter apps at all. */
        boolean isGloballyEnabled();

        /** @return true if the feature is enabled for the given package. */
        boolean packageIsEnabled(AndroidPackage pkg);

        /** @return true if debug logging is enabled for the given package. */
        boolean isLoggingEnabled(int appId);

        /**
         * Turns on logging for the given appId
         *
         * @param enable true if logging should be enabled, false if disabled.
         */
        void enableLogging(int appId, boolean enable);

        /**
         * Initializes the package enablement state for the given package. This gives opportunity
         * to do any expensive operations ahead of the actual checks.
         *
         * @param removed true if adding, false if removing
         */
        void updatePackageState(PackageStateInternal setting, boolean removed);
    }

    private static class FeatureConfigImpl implements FeatureConfig, CompatChange.ChangeListener {
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
            AndroidPackage pkg = mPmInternal.getPackage(packageName);
            if (pkg == null) {
                return;
            }
            updateEnabledState(pkg);
            mAppsFilter.updateShouldFilterCacheForPackage(packageName);
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
            } else if (setting.getPkg() != null) {
                updateEnabledState(setting.getPkg());
            }
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
        final StateProvider stateProvider = command -> {
            synchronized (injector.getLock()) {
                command.currentState(injector.getSettings().getPackagesLocked().untrackedStorage(),
                        injector.getUserManagerInternal().getUserInfos());
            }
        };
        AppsFilterImpl appsFilter = new AppsFilterImpl(stateProvider, featureConfig,
                forcedQueryablePackageNames, forceSystemAppsQueryable, null,
                injector.getBackgroundExecutor(), pmInt);
        featureConfig.setAppsFilter(appsFilter);
        return appsFilter;
    }

    public FeatureConfig getFeatureConfig() {
        return mFeatureConfig;
    }

    /** Returns true if the querying package may query for the potential target package */
    private static boolean canQueryViaComponents(AndroidPackage querying,
            AndroidPackage potentialTarget, WatchedArrayList<String> protectedBroadcasts) {
        if (!querying.getQueriesIntents().isEmpty()) {
            for (Intent intent : querying.getQueriesIntents()) {
                if (matchesPackage(intent, potentialTarget, protectedBroadcasts)) {
                    return true;
                }
            }
        }
        if (!querying.getQueriesProviders().isEmpty()
                && matchesProviders(querying.getQueriesProviders(), potentialTarget)) {
            return true;
        }
        return false;
    }

    private static boolean canQueryViaPackage(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        return !querying.getQueriesPackages().isEmpty()
                && querying.getQueriesPackages().contains(potentialTarget.getPackageName());
    }

    private static boolean canQueryAsInstaller(PackageStateInternal querying,
            AndroidPackage potentialTarget) {
        final InstallSource installSource = querying.getInstallSource();
        if (potentialTarget.getPackageName().equals(installSource.installerPackageName)) {
            return true;
        }
        if (!installSource.isInitiatingPackageUninstalled
                && potentialTarget.getPackageName().equals(installSource.initiatingPackageName)) {
            return true;
        }
        return false;
    }

    private static boolean canQueryViaUsesLibrary(AndroidPackage querying,
            AndroidPackage potentialTarget) {
        if (potentialTarget.getLibraryNames().isEmpty()) {
            return false;
        }
        final List<String> libNames = potentialTarget.getLibraryNames();
        for (int i = 0, size = libNames.size(); i < size; i++) {
            final String libName = libNames.get(i);
            if (querying.getUsesLibraries().contains(libName)
                    || querying.getUsesOptionalLibraries().contains(libName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesProviders(
            Set<String> queriesAuthorities, AndroidPackage potentialTarget) {
        for (int p = ArrayUtils.size(potentialTarget.getProviders()) - 1; p >= 0; p--) {
            ParsedProvider provider = potentialTarget.getProviders().get(p);
            if (!provider.isExported()) {
                continue;
            }
            if (provider.getAuthority() == null) {
                continue;
            }
            StringTokenizer authorities = new StringTokenizer(provider.getAuthority(), ";",
                    false);
            while (authorities.hasMoreElements()) {
                if (queriesAuthorities.contains(authorities.nextToken())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesPackage(Intent intent, AndroidPackage potentialTarget,
            WatchedArrayList<String> protectedBroadcasts) {
        if (matchesAnyComponents(
                intent, potentialTarget.getServices(), null /*protectedBroadcasts*/)) {
            return true;
        }
        if (matchesAnyComponents(
                intent, potentialTarget.getActivities(), null /*protectedBroadcasts*/)) {
            return true;
        }
        if (matchesAnyComponents(intent, potentialTarget.getReceivers(), protectedBroadcasts)) {
            return true;
        }
        if (matchesAnyComponents(
                intent, potentialTarget.getProviders(), null /*protectedBroadcasts*/)) {
            return true;
        }
        return false;
    }

    private static boolean matchesAnyComponents(Intent intent,
            List<? extends ParsedMainComponent> components,
            WatchedArrayList<String> protectedBroadcasts) {
        for (int i = ArrayUtils.size(components) - 1; i >= 0; i--) {
            ParsedMainComponent component = components.get(i);
            if (!component.isExported()) {
                continue;
            }
            if (matchesAnyFilter(intent, component, protectedBroadcasts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyFilter(Intent intent, ParsedComponent component,
            WatchedArrayList<String> protectedBroadcasts) {
        List<ParsedIntentInfo> intents = component.getIntents();
        for (int i = ArrayUtils.size(intents) - 1; i >= 0; i--) {
            IntentFilter intentFilter = intents.get(i).getIntentFilter();
            if (matchesIntentFilter(intent, intentFilter, protectedBroadcasts)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesIntentFilter(Intent intent, IntentFilter intentFilter,
            @Nullable WatchedArrayList<String> protectedBroadcasts) {
        return intentFilter.match(intent.getAction(), intent.getType(), intent.getScheme(),
                intent.getData(), intent.getCategories(), "AppsFilter", true,
                protectedBroadcasts != null ? protectedBroadcasts.untrackedStorage() : null) > 0;
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
            if (changed && DEBUG_LOGGING) {
                Slog.i(TAG, (retainOnUpdate ? "retained " : "") + "implicit access granted: "
                        + recipientUid + " -> " + visibleUid);
            }
        }
        synchronized (mCacheLock) {
            // update the cache in a one-off manner since we've got all the information we need.
            mShouldFilterCache.put(recipientUid, visibleUid, false);
        }
        if (changed) {
            onChanged();
        }
        return changed;
    }

    public void onSystemReady() {
        mOverlayReferenceMapper.rebuildIfDeferred();
        mFeatureConfig.onSystemReady();

        updateEntireShouldFilterCacheAsync();
        onChanged();
    }

    /**
     * Adds a package that should be considered when filtering visibility between apps.
     *
     * @param newPkgSetting the new setting being added
     * @param isReplace     if the package is being replaced and may need extra cleanup.
     */
    public void addPackage(PackageStateInternal newPkgSetting, boolean isReplace) {
        if (DEBUG_TRACING) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "filter.addPackage");
        }
        try {
            if (isReplace) {
                // let's first remove any prior rules for this package
                removePackage(newPkgSetting, true /*isReplace*/);
            }
            mStateProvider.runWithState((settings, users) -> {
                ArraySet<String> additionalChangedPackages =
                        addPackageInternal(newPkgSetting, settings);
                synchronized (mCacheLock) {
                    updateShouldFilterCacheForPackage(mShouldFilterCache, null, newPkgSetting,
                            settings, users, USER_ALL, settings.size());
                    if (additionalChangedPackages != null) {
                        for (int index = 0; index < additionalChangedPackages.size(); index++) {
                            String changedPackage = additionalChangedPackages.valueAt(index);
                            PackageStateInternal changedPkgSetting =
                                    settings.get(changedPackage);
                            if (changedPkgSetting == null) {
                                // It's possible for the overlay mapper to know that an actor
                                // package changed via an explicit reference, even if the actor
                                // isn't installed, so skip if that's the case.
                                continue;
                            }

                            updateShouldFilterCacheForPackage(mShouldFilterCache, null,
                                    changedPkgSetting, settings, users, USER_ALL,
                                    settings.size());
                        }
                    }
                }
            });
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
            synchronized (mLock) {
                for (PackageStateInternal setting : existingSettings.values()) {
                    if (isSystemSigned(mSystemSigningDetails, setting)) {
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

    private void updateEntireShouldFilterCache() {
        updateEntireShouldFilterCache(USER_ALL);
    }

    private void updateEntireShouldFilterCache(int subjectUserId) {
        mStateProvider.runWithState((settings, users) -> {
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
            WatchedSparseBooleanMatrix cache =
                    updateEntireShouldFilterCacheInner(settings, users, userId);
            synchronized (mCacheLock) {
                mShouldFilterCache.copyFrom(cache);
            }
        });
    }

    @NonNull
    private WatchedSparseBooleanMatrix updateEntireShouldFilterCacheInner(
            ArrayMap<String, ? extends PackageStateInternal> settings, UserInfo[] users,
            int subjectUserId) {
        final WatchedSparseBooleanMatrix cache;
        if (subjectUserId == USER_ALL) {
            cache = new WatchedSparseBooleanMatrix(users.length * settings.size());
        } else {
            synchronized (mCacheLock) {
                cache = mShouldFilterCache.snapshot();
            }
            cache.setCapacity(users.length * settings.size());
        }
        for (int i = settings.size() - 1; i >= 0; i--) {
            updateShouldFilterCacheForPackage(cache,
                    null /*skipPackage*/, settings.valueAt(i), settings, users, subjectUserId, i);
        }
        return cache;
    }

    private void updateEntireShouldFilterCacheAsync() {
        mBackgroundExecutor.execute(() -> {
            final ArrayMap<String, PackageStateInternal> settingsCopy = new ArrayMap<>();
            final ArrayMap<String, AndroidPackage> packagesCache = new ArrayMap<>();
            final UserInfo[][] usersRef = new UserInfo[1][];
            mStateProvider.runWithState((settings, users) -> {
                packagesCache.ensureCapacity(settings.size());
                settingsCopy.putAll(settings);
                usersRef[0] = users;
                // store away the references to the immutable packages, since settings are retained
                // during updates.
                for (int i = 0, max = settings.size(); i < max; i++) {
                    final AndroidPackage pkg = settings.valueAt(i).getPkg();
                    packagesCache.put(settings.keyAt(i), pkg);
                }
            });
            WatchedSparseBooleanMatrix cache = updateEntireShouldFilterCacheInner(
                    settingsCopy, usersRef[0], USER_ALL);
            boolean[] changed = new boolean[1];
            // We have a cache, let's make sure the world hasn't changed out from under us.
            mStateProvider.runWithState((settings, users) -> {
                if (settings.size() != settingsCopy.size()) {
                    changed[0] = true;
                    return;
                }
                for (int i = 0, max = settings.size(); i < max; i++) {
                    final AndroidPackage pkg = settings.valueAt(i).getPkg();
                    if (!Objects.equals(pkg, packagesCache.get(settings.keyAt(i)))) {
                        changed[0] = true;
                        return;
                    }
                }
            });
            if (changed[0]) {
                // Something has changed, just update the cache inline with the lock held
                updateEntireShouldFilterCache();
                if (DEBUG_LOGGING) {
                    Slog.i(TAG, "Rebuilding cache with lock due to package change.");
                }
            } else {
                synchronized (mCacheLock) {
                    mShouldFilterCache.copyFrom(cache);
                }
            }
        });
    }

    public void onUserCreated(int newUserId) {
        updateEntireShouldFilterCache(newUserId);
        onChanged();
    }

    public void onUserDeleted(@UserIdInt int userId) {
        removeShouldFilterCacheForUser(userId);
        onChanged();
    }

    private void updateShouldFilterCacheForPackage(String packageName) {
        mStateProvider.runWithState((settings, users) -> {
            synchronized (mCacheLock) {
                updateShouldFilterCacheForPackage(mShouldFilterCache, null /* skipPackage */,
                        settings.get(packageName), settings, users, USER_ALL,
                        settings.size() /*maxIndex*/);
            }
        });
    }

    private void updateShouldFilterCacheForPackage(@NonNull WatchedSparseBooleanMatrix cache,
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
                    updateShouldFilterCacheForUser(cache, subjectSetting, allUsers, otherSetting,
                            allUsers[su].id);
                }
            } else {
                updateShouldFilterCacheForUser(cache, subjectSetting, allUsers, otherSetting,
                        subjectUserId);
            }
        }
    }

    private void updateShouldFilterCacheForUser(@NonNull WatchedSparseBooleanMatrix cache,
            PackageStateInternal subjectSetting, UserInfo[] allUsers,
            PackageStateInternal otherSetting, int subjectUserId) {
        for (int ou = 0; ou < allUsers.length; ou++) {
            int otherUser = allUsers[ou].id;
            int subjectUid = UserHandle.getUid(subjectUserId, subjectSetting.getAppId());
            int otherUid = UserHandle.getUid(otherUser, otherSetting.getAppId());
            cache.put(subjectUid, otherUid,
                    shouldFilterApplicationInternal(
                            subjectUid, subjectSetting, otherSetting, otherUser));
            cache.put(otherUid, subjectUid,
                    shouldFilterApplicationInternal(
                            otherUid, otherSetting, subjectSetting, subjectUserId));
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

    /**
     * This method recomputes all component / intent-based visibility and is intended to match the
     * relevant logic of {@link #addPackageInternal(PackageStateInternal, ArrayMap)}
     */
    @GuardedBy("mLock")
    private void recomputeComponentVisibility(
            ArrayMap<String, ? extends PackageStateInternal> existingSettings) {
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
        mQueriesViaComponentRequireRecompute = false;
    }

    /**
     * See {@link AppsFilterSnapshot#getVisibilityAllowList(PackageStateInternal, int[], ArrayMap)}
     */
    @Override
    @Nullable
    public SparseArray<int[]> getVisibilityAllowList(PackageStateInternal setting, int[] users,
            ArrayMap<String, ? extends PackageStateInternal> existingSettings) {
        synchronized (mLock) {
            if (mForceQueryable.contains(setting.getAppId())) {
                return null;
            }
        }
        // let's reserve max memory to limit the number of allocations
        SparseArray<int[]> result = new SparseArray<>(users.length);
        for (int u = 0; u < users.length; u++) {
            final int userId = users[u];
            int[] appIds = new int[existingSettings.size()];
            int[] buffer = null;
            int allowListSize = 0;
            for (int i = existingSettings.size() - 1; i >= 0; i--) {
                final PackageStateInternal existingSetting = existingSettings.valueAt(i);
                final int existingAppId = existingSetting.getAppId();
                if (existingAppId < Process.FIRST_APPLICATION_UID) {
                    continue;
                }
                final int loc = Arrays.binarySearch(appIds, 0, allowListSize, existingAppId);
                if (loc >= 0) {
                    continue;
                }
                final int existingUid = UserHandle.getUid(userId, existingAppId);
                if (!shouldFilterApplication(existingUid, existingSetting, setting, userId)) {
                    if (buffer == null) {
                        buffer = new int[appIds.length];
                    }
                    final int insert = ~loc;
                    System.arraycopy(appIds, insert, buffer, 0, allowListSize - insert);
                    appIds[insert] = existingAppId;
                    System.arraycopy(buffer, 0, appIds, insert + 1, allowListSize - insert);
                    allowListSize++;
                }
            }
            result.put(userId, Arrays.copyOf(appIds, allowListSize));
        }
        return result;
    }

    /**
     * This api does type conversion on the <existingSettings> parameter.
     */
    @VisibleForTesting(visibility = PRIVATE)
    @Nullable
    SparseArray<int[]> getVisibilityAllowList(PackageStateInternal setting, int[] users,
            WatchedArrayMap<String, ? extends PackageStateInternal> existingSettings) {
        return getVisibilityAllowList(setting, users, existingSettings.untrackedStorage());
    }

    /**
     * Equivalent to calling {@link #addPackage(PackageStateInternal, boolean)} with
     * {@code isReplace} equal to {@code false}.
     *
     * @see AppsFilterImpl#addPackage(PackageStateInternal, boolean)
     */
    public void addPackage(PackageStateInternal newPkgSetting) {
        addPackage(newPkgSetting, false /* isReplace */);
    }

    /**
     * Removes a package for consideration when filtering visibility between apps.
     *
     * @param setting   the setting of the package being removed.
     * @param isReplace if the package is being replaced.
     */
    public void removePackage(PackageStateInternal setting, boolean isReplace) {
        mStateProvider.runWithState((settings, users) -> {
            final ArraySet<String> additionalChangedPackages;
            synchronized (mLock) {
                final int userCount = users.length;
                for (int u = 0; u < userCount; u++) {
                    final int userId = users[u].id;
                    final int removingUid = UserHandle.getUid(userId, setting.getAppId());
                    mImplicitlyQueryable.remove(removingUid);
                    for (int i = mImplicitlyQueryable.size() - 1; i >= 0; i--) {
                        mImplicitlyQueryable.remove(mImplicitlyQueryable.keyAt(i), removingUid);
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
                    mQueriesViaPackage.remove(mQueriesViaPackage.keyAt(i), setting.getAppId());
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

                additionalChangedPackages =
                        mOverlayReferenceMapper.removePkg(setting.getPackageName());

                mFeatureConfig.updatePackageState(setting, true /*removed*/);
            }

            // After removing all traces of the package, if it's part of a shared user, re-add other
            // shared user members to re-establish visibility between them and other packages.
            // NOTE: this must come after all removals from data structures but before we update the
            //       cache
            if (setting.hasSharedUser()) {
                final ArraySet<PackageStateInternal> sharedUserPackages =
                        mPmInternal.getSharedUserPackages(setting.getSharedUserAppId());
                for (int i = sharedUserPackages.size() - 1; i >= 0; i--) {
                    if (sharedUserPackages.valueAt(i) == setting) {
                        continue;
                    }
                    addPackageInternal(
                            sharedUserPackages.valueAt(i), settings);
                }
            }

            removeAppIdFromVisibilityCache(setting.getAppId());
            if (setting.hasSharedUser()) {
                final ArraySet<PackageStateInternal> sharedUserPackages =
                        mPmInternal.getSharedUserPackages(setting.getSharedUserAppId());
                for (int i = sharedUserPackages.size() - 1; i >= 0; i--) {
                    PackageStateInternal siblingSetting =
                            sharedUserPackages.valueAt(i);
                    if (siblingSetting == setting) {
                        continue;
                    }
                    synchronized (mCacheLock) {
                        updateShouldFilterCacheForPackage(mShouldFilterCache,
                                setting.getPackageName(), siblingSetting, settings, users,
                                USER_ALL, settings.size());
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
                        updateShouldFilterCacheForPackage(mShouldFilterCache, null,
                                changedPkgSetting, settings, users, USER_ALL, settings.size());
                    }
                }
            }
            onChanged();
        });
    }

    /**
     * See {@link AppsFilterSnapshot#shouldFilterApplication(int, Object, PackageStateInternal,
     * int)}
     */
    @Override
    public boolean shouldFilterApplication(int callingUid, @Nullable Object callingSetting,
            PackageStateInternal targetPkgSetting, int userId) {
        if (DEBUG_TRACING) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplication");
        }
        try {
            int callingAppId = UserHandle.getAppId(callingUid);
            if (callingAppId < Process.FIRST_APPLICATION_UID
                    || targetPkgSetting.getAppId() < Process.FIRST_APPLICATION_UID
                    || callingAppId == targetPkgSetting.getAppId()) {
                return false;
            }
            final boolean shouldUseCache;
            synchronized (mCacheLock) {
                shouldUseCache = mShouldFilterCache.size() != 0;
            }
            if (shouldUseCache) { // use cache
                if (!shouldFilterApplicationUsingCache(callingUid, targetPkgSetting.getAppId(),
                        userId)) {
                    return false;
                }
            } else {
                if (!shouldFilterApplicationInternal(
                        callingUid, callingSetting, targetPkgSetting, userId)) {
                    return false;
                }
            }

            if (DEBUG_LOGGING || mFeatureConfig.isLoggingEnabled(callingAppId)) {
                log(callingSetting, targetPkgSetting, "BLOCKED");
            }
            return !DEBUG_ALLOW_ALL;
        } finally {
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }
    }

    private boolean shouldFilterApplicationUsingCache(int callingUid, int appId, int userId) {
        synchronized (mCacheLock) {
            final int callingIndex = mShouldFilterCache.indexOfKey(callingUid);
            if (callingIndex < 0) {
                Slog.wtf(TAG, "Encountered calling uid with no cached rules: "
                        + callingUid);
                return true;
            }
            final int targetUid = UserHandle.getUid(userId, appId);
            final int targetIndex = mShouldFilterCache.indexOfKey(targetUid);
            if (targetIndex < 0) {
                Slog.w(TAG, "Encountered calling -> target with no cached rules: "
                        + callingUid + " -> " + targetUid);
                return true;
            }
            return mShouldFilterCache.valueAt(callingIndex, targetIndex);
        }
    }

    private boolean shouldFilterApplicationInternal(int callingUid, Object callingSetting,
            PackageStateInternal targetPkgSetting, int targetUserId) {
        if (DEBUG_TRACING) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplicationInternal");
        }
        try {
            final boolean featureEnabled = mFeatureConfig.isGloballyEnabled();
            if (!featureEnabled) {
                if (DEBUG_LOGGING) {
                    Slog.d(TAG, "filtering disabled; skipped");
                }
                return false;
            }
            if (callingSetting == null) {
                Slog.wtf(TAG, "No setting found for non system uid " + callingUid);
                return true;
            }
            final PackageStateInternal callingPkgSetting;
            final ArraySet<? extends PackageStateInternal> callingSharedPkgSettings;
            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "callingSetting instanceof");
            }
            if (callingSetting instanceof PackageStateInternal) {
                final PackageStateInternal packageState = (PackageStateInternal) callingSetting;
                if (packageState.hasSharedUser()) {
                    callingPkgSetting = null;
                    callingSharedPkgSettings = mPmInternal.getSharedUserPackages(
                            packageState.getSharedUserAppId());
                } else {
                    callingPkgSetting = packageState;
                    callingSharedPkgSettings = null;
                }
            } else {
                callingPkgSetting = null;
                callingSharedPkgSettings = ((SharedUserSetting) callingSetting).getPackageStates();
            }
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }

            if (callingPkgSetting != null) {
                if (callingPkgSetting.getPkg() != null
                        && !mFeatureConfig.packageIsEnabled(callingPkgSetting.getPkg())) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "DISABLED");
                    }
                    return false;
                }
            } else {
                for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                    final AndroidPackage pkg = callingSharedPkgSettings.valueAt(i).getPkg();
                    if (pkg != null && !mFeatureConfig.packageIsEnabled(pkg)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "DISABLED");
                        }
                        return false;
                    }
                }
            }

            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "getAppId");
            }
            final int callingAppId;
            if (callingPkgSetting != null) {
                callingAppId = callingPkgSetting.getAppId();
            } else {
                // all should be the same
                callingAppId = callingSharedPkgSettings.valueAt(0).getAppId();
            }
            final int targetAppId = targetPkgSetting.getAppId();
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
            if (callingAppId == targetAppId) {
                if (DEBUG_LOGGING) {
                    log(callingSetting, targetPkgSetting, "same app id");
                }
                return false;
            }

            try {
                if (DEBUG_TRACING) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "requestsQueryAllPackages");
                }
                if (callingPkgSetting != null) {
                    if (callingPkgSetting.getPkg() != null
                            && requestsQueryAllPackages(callingPkgSetting.getPkg())) {
                        return false;
                    }
                } else {
                    for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                        AndroidPackage pkg = callingSharedPkgSettings.valueAt(i).getPkg();
                        if (pkg != null && requestsQueryAllPackages(pkg)) {
                            return false;
                        }
                    }
                }
            } finally {
                if (DEBUG_TRACING) {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            }

            // This package isn't technically installed and won't be written to settings, so we can
            // treat it as filtered until it's available again.
            final AndroidPackage targetPkg = targetPkgSetting.getPkg();
            if (targetPkg == null) {
                if (DEBUG_LOGGING) {
                    Slog.wtf(TAG, "shouldFilterApplication: " + "targetPkg is null");
                }
                return true;
            }
            if (targetPkg.isStaticSharedLibrary()) {
                // not an app, this filtering takes place at a higher level
                return false;
            }

            synchronized (mLock) {
                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mForceQueryable");
                    }
                    if (mForceQueryable.contains(targetAppId)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "force queryable");
                        }
                        return false;
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }
                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mQueriesViaPackage");
                    }
                    if (mQueriesViaPackage.contains(callingAppId, targetAppId)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "queries package");
                        }
                        return false;
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }
                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mQueriesViaComponent");
                    }
                    if (mQueriesViaComponentRequireRecompute) {
                        mStateProvider.runWithState((settings, users) -> {
                            synchronized (mLock) {
                                recomputeComponentVisibility(settings);
                            }
                        });
                    }
                    if (mQueriesViaComponent.contains(callingAppId, targetAppId)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "queries component");
                        }
                        return false;
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }

                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mImplicitlyQueryable");
                    }
                    final int targetUid = UserHandle.getUid(targetUserId, targetAppId);
                    if (mImplicitlyQueryable.contains(callingUid, targetUid)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "implicitly queryable for user");
                        }
                        return false;
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }

                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mRetainedImplicitlyQueryable");
                    }
                    final int targetUid = UserHandle.getUid(targetUserId, targetAppId);
                    if (mRetainedImplicitlyQueryable.contains(callingUid, targetUid)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting,
                                    "retained implicitly queryable for user");
                        }
                        return false;
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }

                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mOverlayReferenceMapper");
                    }
                    final String targetName = targetPkg.getPackageName();
                    if (callingSharedPkgSettings != null) {
                        int size = callingSharedPkgSettings.size();
                        for (int index = 0; index < size; index++) {
                            PackageStateInternal pkgSetting = callingSharedPkgSettings.valueAt(
                                    index);
                            if (mOverlayReferenceMapper.isValidActor(targetName,
                                    pkgSetting.getPackageName())) {
                                if (DEBUG_LOGGING) {
                                    log(callingPkgSetting, targetPkgSetting,
                                            "matches shared user of package that acts on target of "
                                                    + "overlay");
                                }
                                return false;
                            }
                        }
                    } else {
                        if (mOverlayReferenceMapper.isValidActor(targetName,
                                callingPkgSetting.getPackageName())) {
                            if (DEBUG_LOGGING) {
                                log(callingPkgSetting, targetPkgSetting,
                                        "acts on target of overlay");
                            }
                            return false;
                        }
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }

                try {
                    if (DEBUG_TRACING) {
                        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mQueryableViaUsesLibrary");
                    }
                    if (mQueryableViaUsesLibrary.contains(callingAppId, targetAppId)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "queryable for library users");
                        }
                        return false;
                    }
                } finally {
                    if (DEBUG_TRACING) {
                        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                    }
                }
            }
            return true;
        } finally {
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
        }
    }

    /**
     * See {@link AppsFilterSnapshot#canQueryPackage(AndroidPackage, String)}
     */
    @Override
    public boolean canQueryPackage(@NonNull AndroidPackage querying, String potentialTarget) {
        int appId = UserHandle.getAppId(querying.getUid());
        if (appId < Process.FIRST_APPLICATION_UID) {
            return true;
        }

        // Check if FILTER_APPLICATION_QUERY is enabled on the given package.
        if (!mFeatureConfig.packageIsEnabled(querying)) {
            return true;
        }

        if (requestsQueryAllPackages(querying)) {
            return true;
        }

        return !querying.getQueriesPackages().isEmpty()
                && querying.getQueriesPackages().contains(potentialTarget);
    }

    private static boolean requestsQueryAllPackages(@NonNull AndroidPackage pkg) {
        // we're not guaranteed to have permissions yet analyzed at package add, so we inspect the
        // package directly
        return pkg.getRequestedPermissions().contains(
                Manifest.permission.QUERY_ALL_PACKAGES);
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

    private static void log(Object callingSetting, PackageStateInternal targetPkgSetting,
            String description) {
        Slog.i(TAG,
                "interaction: " + (callingSetting == null ? "system" : callingSetting) + " -> "
                        + targetPkgSetting + " " + description);
    }

    /**
     * See {@link AppsFilterSnapshot#dumpQueries(PrintWriter, Integer, DumpState, int[],
     * QuadFunction)}
     */
    @Override
    public void dumpQueries(
            PrintWriter pw, @Nullable Integer filteringAppId, DumpState dumpState, int[] users,
            QuadFunction<Integer, Integer, Integer, Boolean, String[]> getPackagesForUid) {
        final SparseArray<String> cache = new SparseArray<>();
        ToString<Integer> expandPackages = input -> {
            String cachedValue = cache.get(input);
            if (cachedValue == null) {
                final int callingUid = Binder.getCallingUid();
                final int appId = UserHandle.getAppId(input);
                String[] packagesForUid = null;
                for (int i = 0, size = users.length; packagesForUid == null && i < size; i++) {
                    packagesForUid = getPackagesForUid.apply(callingUid, users[i], appId,
                            false /*isCallerInstantApp*/);
                }
                if (packagesForUid == null) {
                    cachedValue = "[app id " + input + " not installed]";
                } else {
                    cachedValue = packagesForUid.length == 1 ? packagesForUid[0]
                            : "[" + TextUtils.join(",", packagesForUid) + "]";
                }
                cache.put(input, cachedValue);
            }
            return cachedValue;
        };
        pw.println();
        pw.println("Queries:");
        dumpState.onTitlePrinted();
        if (!mFeatureConfig.isGloballyEnabled()) {
            pw.println("  DISABLED");
            if (!DEBUG_LOGGING) {
                return;
            }
        }
        pw.println("  system apps queryable: " + mSystemAppsQueryable);
        synchronized (mLock) {
            dumpPackageSet(pw, filteringAppId, mForceQueryable.untrackedStorage(),
                    "forceQueryable", "  ", expandPackages);
            pw.println("  queries via package name:");
            dumpQueriesMap(pw, filteringAppId, mQueriesViaPackage, "    ", expandPackages);
            pw.println("  queries via component:");
            dumpQueriesMap(pw, filteringAppId, mQueriesViaComponent, "    ", expandPackages);
            pw.println("  queryable via interaction:");
            for (int user : users) {
                pw.append("    User ").append(Integer.toString(user)).println(":");
                dumpQueriesMap(pw,
                        filteringAppId == null ? null : UserHandle.getUid(user, filteringAppId),
                        mImplicitlyQueryable, "      ", expandPackages);
                dumpQueriesMap(pw,
                        filteringAppId == null ? null : UserHandle.getUid(user, filteringAppId),
                        mRetainedImplicitlyQueryable, "      ", expandPackages);
            }
            pw.println("  queryable via uses-library:");
            dumpQueriesMap(pw, filteringAppId, mQueryableViaUsesLibrary, "    ", expandPackages);
        }
    }

    private static void dumpQueriesMap(PrintWriter pw, @Nullable Integer filteringId,
            WatchedSparseSetArray<Integer> queriesMap, String spacing,
            @Nullable ToString<Integer> toString) {
        for (int i = 0; i < queriesMap.size(); i++) {
            Integer callingId = queriesMap.keyAt(i);
            if (Objects.equals(callingId, filteringId)) {
                // don't filter target package names if the calling is filteringId
                dumpPackageSet(
                        pw, null /*filteringId*/, queriesMap.get(callingId),
                        toString == null
                                ? callingId.toString()
                                : toString.toString(callingId),
                        spacing, toString);
            } else {
                dumpPackageSet(
                        pw, filteringId, queriesMap.get(callingId),
                        toString == null
                                ? callingId.toString()
                                : toString.toString(callingId),
                        spacing, toString);
            }
        }
    }

    private interface ToString<T> {
        String toString(T input);
    }

    private static <T> void dumpPackageSet(PrintWriter pw, @Nullable T filteringId,
            ArraySet<T> targetPkgSet, String subTitle, String spacing,
            @Nullable ToString<T> toString) {
        if (targetPkgSet != null && targetPkgSet.size() > 0
                && (filteringId == null || targetPkgSet.contains(filteringId))) {
            pw.append(spacing).append(subTitle).println(":");
            for (T item : targetPkgSet) {
                if (filteringId == null || Objects.equals(filteringId, item)) {
                    pw.append(spacing).append("  ")
                            .println(toString == null ? item : toString.toString(item));
                }
            }
        }
    }
}
