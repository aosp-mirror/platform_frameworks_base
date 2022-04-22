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
     * Guards the accesses for the list/set class members
     */
    protected final Object mLock = new Object();
    /**
     * Guards the access for {@link AppsFilterBase#mShouldFilterCache};
     */
    protected Object mCacheLock = new Object();

    @Override
    protected boolean isForceQueryable(int appId) {
        synchronized (mLock) {
            return super.isForceQueryable(appId);
        }
    }

    @Override
    protected boolean isQueryableViaPackage(int callingAppId, int targetAppId) {
        synchronized (mLock) {
            return super.isQueryableViaPackage(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isQueryableViaComponent(int callingAppId, int targetAppId) {
        synchronized (mLock) {
            return super.isQueryableViaComponent(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isImplicitlyQueryable(int callingAppId, int targetAppId) {
        synchronized (mLock) {
            return super.isImplicitlyQueryable(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isRetainedImplicitlyQueryable(int callingAppId, int targetAppId) {
        synchronized (mLock) {
            return super.isRetainedImplicitlyQueryable(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean isQueryableViaUsesLibrary(int callingAppId, int targetAppId) {
        synchronized (mLock) {
            return super.isQueryableViaUsesLibrary(callingAppId, targetAppId);
        }
    }

    @Override
    protected boolean shouldFilterApplicationUsingCache(int callingUid, int appId, int userId) {
        synchronized (mCacheLock) {
            return super.shouldFilterApplicationUsingCache(callingUid, appId, userId);
        }
    }

    @Override
    protected void dumpQueryables(PrintWriter pw, @Nullable Integer filteringAppId, int[] users,
            ToString<Integer> expandPackages) {
        synchronized (mLock) {
            dumpQueryables(pw, filteringAppId, users, expandPackages);
        }
    }
}
