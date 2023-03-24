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

import android.annotation.Nullable;

import java.io.PrintWriter;

/**
 * Overrides the unlocked methods in {@link AppsFilterBase} and guards them with locks.
 * These are used by {@link AppsFilterImpl} which contains modifications to the class members
 */
abstract class AppsFilterLocked extends AppsFilterBase {
    /**
     * The following locks guard the accesses for the list/set class members
     */
    protected final PackageManagerTracedLock mForceQueryableLock =
            new PackageManagerTracedLock();
    protected final PackageManagerTracedLock mQueriesViaPackageLock =
            new PackageManagerTracedLock();
    protected final PackageManagerTracedLock mQueriesViaComponentLock =
            new PackageManagerTracedLock();
    /**
     * This lock covers both {@link #mImplicitlyQueryable} and {@link #mRetainedImplicitlyQueryable}
     */
    protected final PackageManagerTracedLock mImplicitlyQueryableLock =
        new PackageManagerTracedLock();
    protected final PackageManagerTracedLock mQueryableViaUsesLibraryLock =
        new PackageManagerTracedLock();
    protected final PackageManagerTracedLock mProtectedBroadcastsLock =
        new PackageManagerTracedLock();
    protected final PackageManagerTracedLock mQueryableViaUsesPermissionLock =
        new PackageManagerTracedLock();

    /**
     * Guards the access for {@link AppsFilterBase#mShouldFilterCache};
     */
    protected final PackageManagerTracedLock mCacheLock = new PackageManagerTracedLock();

    @Override
    protected boolean isForceQueryable(int appId) {
        synchronized (mForceQueryableLock) {
            return super.isForceQueryable(appId);
        }
    }

    @Override
    protected boolean isQueryableViaPackage(int callingAppId, int targetAppId) {
        synchronized (mQueriesViaPackageLock) {
            return super.isQueryableViaPackage(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isQueryableViaComponent(int callingAppId, int targetAppId) {
        synchronized (mQueriesViaComponentLock) {
            return super.isQueryableViaComponent(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isImplicitlyQueryable(int callingUid, int targetUid) {
        synchronized (mImplicitlyQueryableLock) {
            return super.isImplicitlyQueryable(callingUid, targetUid);
        }
    }

    @Override
    protected boolean isRetainedImplicitlyQueryable(int callingUid, int targetUid) {
        synchronized (mImplicitlyQueryableLock) {
            return super.isRetainedImplicitlyQueryable(callingUid, targetUid);
        }
    }

    @Override
    protected boolean isQueryableViaUsesLibrary(int callingAppId, int targetAppId) {
        synchronized (mQueryableViaUsesLibraryLock) {
            return super.isQueryableViaUsesLibrary(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isQueryableViaUsesPermission(int callingAppId, int targetAppId) {
        synchronized (mQueryableViaUsesPermissionLock) {
            return super.isQueryableViaUsesPermission(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean shouldFilterApplicationUsingCache(int callingUid, int appId, int userId) {
        synchronized (mCacheLock) {
            return super.shouldFilterApplicationUsingCache(callingUid, appId, userId);
        }
    }

    @Override
    protected void dumpForceQueryable(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        synchronized (mForceQueryableLock) {
            super.dumpForceQueryable(pw, filteringAppId, expandPackages);
        }
    }

    @Override
    protected void dumpQueriesViaPackage(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        synchronized (mQueriesViaPackageLock) {
            super.dumpQueriesViaPackage(pw, filteringAppId, expandPackages);
        }
    }

    @Override
    protected void dumpQueriesViaComponent(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        synchronized (mQueriesViaComponentLock) {
            super.dumpQueriesViaComponent(pw, filteringAppId, expandPackages);
        }
    }

    @Override
    protected void dumpQueriesViaImplicitlyQueryable(PrintWriter pw,
            @Nullable Integer filteringAppId, int[] users, ToString<Integer> expandPackages) {
        synchronized (mImplicitlyQueryableLock) {
            super.dumpQueriesViaImplicitlyQueryable(pw, filteringAppId, users, expandPackages);
        }
    }

    @Override
    protected void dumpQueriesViaUsesLibrary(PrintWriter pw, @Nullable Integer filteringAppId,
            ToString<Integer> expandPackages) {
        synchronized (mQueryableViaUsesLibraryLock) {
            super.dumpQueriesViaUsesLibrary(pw, filteringAppId, expandPackages);
        }
    }
}
