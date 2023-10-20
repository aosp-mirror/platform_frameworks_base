/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.server.pm.AppsFilterUtils.canQueryViaComponents;
import static com.android.server.pm.AppsFilterUtils.requestsQueryAllPackages;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.SigningDetails;
import android.os.Binder;
import android.os.Handler;
import android.os.Process;
import android.os.Trace;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.QuadFunction;
import com.android.server.om.OverlayReferenceMapper;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;
import com.android.server.pm.pkg.SharedUserApi;
import com.android.server.pm.snapshot.PackageDataSnapshot;
import com.android.server.utils.SnapshotCache;
import com.android.server.utils.Watched;
import com.android.server.utils.WatchedArrayMap;
import com.android.server.utils.WatchedArraySet;
import com.android.server.utils.WatchedSparseBooleanMatrix;
import com.android.server.utils.WatchedSparseSetArray;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AppsFilter is the entity responsible for filtering visibility between apps based on declarations
 * in their manifests. This class implements the unlocked, read-only methods of AppsFilter.
 * See {@link AppsFilterImpl} for the write methods that updates the internal structures.
 */
public abstract class AppsFilterBase implements AppsFilterSnapshot {
    protected static final String TAG = "AppsFilter";

    // Logs all filtering instead of enforcing
    protected static final boolean DEBUG_ALLOW_ALL = false;
    protected static final boolean DEBUG_LOGGING = false;
    public static final boolean DEBUG_TRACING = false;

    // Allow some time for cache rebuilds.
    protected static final int CACHE_REBUILD_DELAY_MIN_MS = 10000;
    // With each new rebuild the delay doubles until it reaches max delay.
    protected static final int CACHE_REBUILD_DELAY_MAX_MS = 10000;

    /**
     * This contains a list of app UIDs that are implicitly queryable because another app explicitly
     * interacted with it. For example, if application A starts a service in application B,
     * application B is implicitly allowed to query for application A; regardless of any manifest
     * entries.
     */
    @NonNull
    @Watched
    protected WatchedSparseSetArray<Integer> mImplicitlyQueryable;
    @NonNull
    protected SnapshotCache<WatchedSparseSetArray<Integer>> mImplicitQueryableSnapshot;

    /**
     * This contains a list of app UIDs that are implicitly queryable because another app explicitly
     * interacted with it, but could keep across package updates. For example, if application A
     * grants persistable uri permission to application B; regardless of any manifest entries.
     */
    @NonNull
    @Watched
    protected WatchedSparseSetArray<Integer> mRetainedImplicitlyQueryable;
    @NonNull
    protected SnapshotCache<WatchedSparseSetArray<Integer>> mRetainedImplicitlyQueryableSnapshot;

    /**
     * A mapping from the set of App IDs that query other App IDs via package name to the
     * list of packages that they can see.
     */
    @NonNull
    @Watched
    protected WatchedSparseSetArray<Integer> mQueriesViaPackage;
    @NonNull
    protected SnapshotCache<WatchedSparseSetArray<Integer>> mQueriesViaPackageSnapshot;

    /**
     * A mapping from the set of App IDs that query others via component match to the list
     * of packages that the they resolve to.
     */
    @NonNull
    @Watched
    protected WatchedSparseSetArray<Integer> mQueriesViaComponent;
    @NonNull
    protected SnapshotCache<WatchedSparseSetArray<Integer>> mQueriesViaComponentSnapshot;

    /**
     * A mapping from the set of App IDs that query other App IDs via library name to the
     * list of packages that they can see.
     */
    @NonNull
    @Watched
    protected WatchedSparseSetArray<Integer> mQueryableViaUsesLibrary;
    @NonNull
    protected SnapshotCache<WatchedSparseSetArray<Integer>> mQueryableViaUsesLibrarySnapshot;

    /**
     * A mapping from the set of App IDs that query other App IDs via custom permissions to the
     * list of packages that they can see.
     */
    @NonNull
    @Watched
    protected WatchedSparseSetArray<Integer> mQueryableViaUsesPermission;
    @NonNull
    protected SnapshotCache<WatchedSparseSetArray<Integer>> mQueryableViaUsesPermissionSnapshot;

    /**
     * Handler for running tasks such as building the initial visibility cache.
     */
    protected Handler mHandler;

    /**
     * Pending full recompute of mQueriesViaComponent. Occurs when a package adds a new set of
     * protected broadcast. This in turn invalidates all prior additions and require a very
     * computationally expensive recomputing.
     * Full recompute is done lazily at the point when we use mQueriesViaComponent to filter apps.
     */
    protected AtomicBoolean mQueriesViaComponentRequireRecompute = new AtomicBoolean(false);

    /**
     * A set of App IDs that are always queryable by any package, regardless of their manifest
     * content.
     */
    @NonNull
    @Watched
    protected WatchedArraySet<Integer> mForceQueryable;
    @NonNull
    protected SnapshotCache<WatchedArraySet<Integer>> mForceQueryableSnapshot;

    /**
     * The set of package names provided by the device that should be force queryable regardless of
     * their manifest contents.
     */
    @NonNull
    protected String[] mForceQueryableByDevicePackageNames;
    @NonNull
    /** True if all system apps should be made queryable by default. */
    protected boolean mSystemAppsQueryable;
    @NonNull
    protected FeatureConfig mFeatureConfig;
    @NonNull
    protected OverlayReferenceMapper mOverlayReferenceMapper;
    @Nullable
    protected SigningDetails mSystemSigningDetails;

    @NonNull
    @Watched
    protected WatchedArraySet<String> mProtectedBroadcasts;
    @NonNull
    protected SnapshotCache<WatchedArraySet<String>> mProtectedBroadcastsSnapshot;

    /**
     * This structure maps uid -> uid and indicates whether access from the first should be
     * filtered to the second. It's essentially a cache of the
     * {@link #shouldFilterApplicationInternal(PackageDataSnapshot, int, Object,
     * PackageStateInternal, int)} call.
     * NOTE: It can only be relied upon after the system is ready to avoid unnecessary update on
     * initial scam and is empty until {@link #mCacheReady} is true.
     */
    @NonNull
    @Watched
    protected WatchedSparseBooleanMatrix mShouldFilterCache;
    @NonNull
    protected SnapshotCache<WatchedSparseBooleanMatrix> mShouldFilterCacheSnapshot;

    protected volatile boolean mCacheReady = false;
    protected volatile boolean mCacheEnabled = true;
    protected volatile boolean mNeedToUpdateCacheForImplicitAccess = false;

    protected static final boolean CACHE_VALID = true;
    protected static final boolean CACHE_INVALID = false;
    protected final AtomicBoolean mCacheValid = new AtomicBoolean(CACHE_INVALID);

    protected boolean isForceQueryable(int callingAppId) {
        return mForceQueryable.contains(callingAppId);
    }

    protected boolean isQueryableViaPackage(int callingAppId, int targetAppId) {
        return mQueriesViaPackage.contains(callingAppId, targetAppId);
    }

    protected boolean isQueryableViaComponent(int callingAppId, int targetAppId) {
        return mQueriesViaComponent.contains(callingAppId, targetAppId);
    }

    protected boolean isImplicitlyQueryable(int callingUid, int targetUid) {
        return mImplicitlyQueryable.contains(callingUid, targetUid);
    }

    protected boolean isRetainedImplicitlyQueryable(int callingUid, int targetUid) {
        return mRetainedImplicitlyQueryable.contains(callingUid, targetUid);
    }

    protected boolean isQueryableViaUsesLibrary(int callingAppId, int targetAppId) {
        return mQueryableViaUsesLibrary.contains(callingAppId, targetAppId);
    }

    protected boolean isQueryableViaUsesPermission(int callingAppId, int targetAppId) {
        return mQueryableViaUsesPermission.contains(callingAppId, targetAppId);
    }

    protected boolean isQueryableViaComponentWhenRequireRecompute(
            ArrayMap<String, ? extends PackageStateInternal> existingSettings,
            PackageStateInternal callingPkgSetting,
            ArraySet<PackageStateInternal> callingSharedPkgSettings,
            AndroidPackage targetPkg,
            int callingAppId, int targetAppId) {
        // Do no recompute or use mQueriesViaComponent if it's stale in snapshot
        // Since we know we are in the snapshot, no need to acquire mLock because
        // mProtectedBroadcasts will not change
        if (callingPkgSetting != null) {
            if (callingPkgSetting.getPkg() != null
                    && canQueryViaComponents(callingPkgSetting.getPkg(), targetPkg,
                    mProtectedBroadcasts)) {
                return true;
            }
        } else {
            for (int i = callingSharedPkgSettings.size() - 1; i >= 0; i--) {
                final AndroidPackage pkg =
                        callingSharedPkgSettings.valueAt(i).getPkg();
                if (pkg != null && canQueryViaComponents(pkg, targetPkg,
                        mProtectedBroadcasts)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * See {@link AppsFilterSnapshot#getVisibilityAllowList(PackageDataSnapshot,
     * PackageStateInternal, int[], ArrayMap)}
     */
    @Override
    @Nullable
    public SparseArray<int[]> getVisibilityAllowList(PackageDataSnapshot snapshot,
            PackageStateInternal setting, int[] users,
            ArrayMap<String, ? extends PackageStateInternal> existingSettings) {
        if (isForceQueryable(setting.getAppId())) {
            return null;
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
                if (!shouldFilterApplication(snapshot, existingUid, existingSetting, setting,
                        userId)) {
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
    SparseArray<int[]> getVisibilityAllowList(PackageDataSnapshot snapshot,
            PackageStateInternal setting, int[] users,
            WatchedArrayMap<String, ? extends PackageStateInternal> existingSettings) {
        return getVisibilityAllowList(snapshot, setting, users,
                existingSettings.untrackedStorage());
    }

    /**
     * See
     * {@link AppsFilterSnapshot#shouldFilterApplication(PackageDataSnapshot, int, Object,
     * PackageStateInternal, int)}
     */
    @Override
    public boolean shouldFilterApplication(PackageDataSnapshot snapshot, int callingUid,
            @Nullable Object callingSetting, PackageStateInternal targetPkgSetting, int userId) {
        if (DEBUG_TRACING) {
            Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "shouldFilterApplication");
        }
        try {
            int callingAppId = UserHandle.getAppId(callingUid);
            if (callingAppId < Process.FIRST_APPLICATION_UID
                    || targetPkgSetting.getAppId() < Process.FIRST_APPLICATION_UID
                    || callingAppId == targetPkgSetting.getAppId()) {
                return false;
            } else if (Process.isSdkSandboxUid(callingAppId)) {
                final int targetAppId = targetPkgSetting.getAppId();
                final int targetUid = UserHandle.getUid(userId, targetAppId);
                // we only allow sdk sandbox processes access to forcequeryable packages
                return !isForceQueryable(targetPkgSetting.getAppId())
                      && !isImplicitlyQueryable(callingUid, targetUid);
            }
            // use cache
            if (mCacheReady && mCacheEnabled) {
                if (!shouldFilterApplicationUsingCache(callingUid,
                        targetPkgSetting.getAppId(),
                        userId)) {
                    return false;
                }
            } else {
                if (!shouldFilterApplicationInternal((Computer) snapshot,
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

    protected boolean shouldFilterApplicationUsingCache(int callingUid, int appId, int userId) {
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

    protected boolean shouldFilterApplicationInternal(Computer snapshot, int callingUid,
            Object callingSetting, PackageStateInternal targetPkgSetting, int targetUserId) {
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

            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "getAppId");
            }
            final int callingAppId = UserHandle.getAppId(callingUid);
            final int targetAppId = targetPkgSetting.getAppId();
            if (DEBUG_TRACING) {
                Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
            }
            if (callingAppId == targetAppId
                    || callingAppId < Process.FIRST_APPLICATION_UID
                    || targetAppId < Process.FIRST_APPLICATION_UID) {
                if (DEBUG_LOGGING) {
                    log(callingSetting, targetPkgSetting, "same app id or core app id");
                }
                return false;
            }

            final PackageStateInternal callingPkgSetting;
            if (DEBUG_TRACING) {
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "callingSetting instanceof");
            }
            final ArraySet<PackageStateInternal> callingSharedPkgSettings = new ArraySet<>();

            if (callingSetting instanceof PackageStateInternal) {
                final PackageStateInternal packageState = (PackageStateInternal) callingSetting;
                if (packageState.hasSharedUser()) {
                    callingPkgSetting = null;
                    final SharedUserApi sharedUserApi =
                            snapshot.getSharedUser(packageState.getSharedUserAppId());
                    if (sharedUserApi != null) {
                        callingSharedPkgSettings.addAll(sharedUserApi.getPackageStates());
                    }
                } else {
                    callingPkgSetting = packageState;
                }
            } else {
                callingPkgSetting = null;
                callingSharedPkgSettings.addAll(
                        ((SharedUserSetting) callingSetting).getPackageStates());
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

            try {
                if (DEBUG_TRACING) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mForceQueryable");
                }
                if (isForceQueryable(targetAppId)) {
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
                if (isQueryableViaPackage(callingAppId, targetAppId)) {
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
                if (!mQueriesViaComponentRequireRecompute.get()) {
                    if (isQueryableViaComponent(callingAppId, targetAppId)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "queries component");
                        }
                        return false;
                    }
                } else { // mQueriesViaComponent is stale
                    if (isQueryableViaComponentWhenRequireRecompute(snapshot.getPackageStates(),
                            callingPkgSetting, callingSharedPkgSettings, targetPkg,
                            callingAppId, targetAppId)) {
                        if (DEBUG_LOGGING) {
                            log(callingSetting, targetPkgSetting, "queries component");
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
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mImplicitlyQueryable");
                }
                final int targetUid = UserHandle.getUid(targetUserId, targetAppId);
                if (isImplicitlyQueryable(callingUid, targetUid)) {
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
                if (isRetainedImplicitlyQueryable(callingUid, targetUid)) {
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
                if (!callingSharedPkgSettings.isEmpty()) {
                    int size = callingSharedPkgSettings.size();
                    for (int index = 0; index < size; index++) {
                        PackageStateInternal pkgSetting = callingSharedPkgSettings.valueAt(index);
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
                            log(callingPkgSetting, targetPkgSetting, "acts on target of overlay");
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
                if (isQueryableViaUsesLibrary(callingAppId, targetAppId)) {
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

            try {
                if (DEBUG_TRACING) {
                    Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "mQueryableViaUsesPermission");
                }
                if (isQueryableViaUsesPermission(callingAppId, targetAppId)) {
                    if (DEBUG_LOGGING) {
                        log(callingSetting, targetPkgSetting, "queryable for permission users");
                    }
                    return false;
                }
            } finally {
                if (DEBUG_TRACING) {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
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
        dumpForceQueryable(pw, filteringAppId, expandPackages);
        dumpQueriesViaPackage(pw, filteringAppId, expandPackages);
        dumpQueriesViaComponent(pw, filteringAppId, expandPackages);
        dumpQueriesViaImplicitlyQueryable(pw, filteringAppId, users, expandPackages);
        dumpQueriesViaUsesLibrary(pw, filteringAppId, expandPackages);
    }

    protected void dumpForceQueryable(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        pw.println("  queries via forceQueryable:");
        dumpPackageSet(pw, filteringAppId, mForceQueryable.untrackedStorage(),
                "forceQueryable", "  ", expandPackages);
    }

    protected void dumpQueriesViaPackage(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        pw.println("  queries via package name:");
        dumpQueriesMap(pw, filteringAppId, mQueriesViaPackage, "    ", expandPackages);
    }

    protected void dumpQueriesViaComponent(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        pw.println("  queries via component:");
        dumpQueriesMap(pw, filteringAppId, mQueriesViaComponent, "    ", expandPackages);
    }

    protected void dumpQueriesViaImplicitlyQueryable(PrintWriter pw,
            @Nullable Integer filteringAppId, int[] users, ToString<Integer> expandPackages) {
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
    }

    protected void dumpQueriesViaUsesLibrary(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        pw.println("  queryable via uses-library:");
        dumpQueriesMap(pw, filteringAppId, mQueryableViaUsesLibrary, "    ",
                expandPackages);
    }

    protected void dumpQueriesViaUsesPermission(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        pw.println("  queryable via uses-permission:");
        dumpQueriesMap(pw, filteringAppId, mQueryableViaUsesPermission, "    ",
                expandPackages);
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

    protected interface ToString<T> {
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
