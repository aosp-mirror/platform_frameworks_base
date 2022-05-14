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

import com.android.server.utils.SnapshotCache;
import com.android.server.utils.WatchedSparseBooleanMatrix;

import java.util.Arrays;

/**
 * Implements the copy-constructor that generates a snapshot of AppsFilter
 */
public final class AppsFilterSnapshotImpl extends AppsFilterBase {
    AppsFilterSnapshotImpl(AppsFilterImpl orig) {
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
        mFeatureConfig = orig.mFeatureConfig.snapshot();
        mOverlayReferenceMapper = orig.mOverlayReferenceMapper;
        mSystemSigningDetails = orig.mSystemSigningDetails;

        mCacheReady = orig.mCacheReady;
        if (mCacheReady) {
            synchronized (orig.mCacheLock) {
                mShouldFilterCache = orig.mShouldFilterCacheSnapshot.snapshot();
            }
        } else {
            // cache is not ready, use an empty cache for the snapshot
            mShouldFilterCache = new WatchedSparseBooleanMatrix();
        }
        mShouldFilterCacheSnapshot = new SnapshotCache.Sealed<>();

        mBackgroundExecutor = null;
    }
}
