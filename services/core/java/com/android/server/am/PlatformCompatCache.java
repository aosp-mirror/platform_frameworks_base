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

package com.android.server.am;

import static com.android.server.am.ActivityManagerService.TAG;
import static com.android.server.am.OomAdjuster.CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID;
import static com.android.server.am.OomAdjuster.PROCESS_CAPABILITY_CHANGE_ID;
import static com.android.server.am.OomAdjuster.USE_SHORT_FGS_USAGE_INTERACTION_TIME;

import android.annotation.IntDef;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArrayMap;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.compat.IPlatformCompat;
import com.android.server.compat.CompatChange;
import com.android.server.compat.PlatformCompat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * Local platform compat cache.
 */
final class PlatformCompatCache {

    static final int CACHED_COMPAT_CHANGE_PROCESS_CAPABILITY = 0;
    static final int CACHED_COMPAT_CHANGE_CAMERA_MICROPHONE_CAPABILITY = 1;
    static final int CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME = 2;

    @IntDef(prefix = { "CACHED_COMPAT_CHANGE_" }, value = {
        CACHED_COMPAT_CHANGE_PROCESS_CAPABILITY,
        CACHED_COMPAT_CHANGE_CAMERA_MICROPHONE_CAPABILITY,
        CACHED_COMPAT_CHANGE_USE_SHORT_FGS_USAGE_INTERACTION_TIME,
    })
    @Retention(RetentionPolicy.SOURCE)
    static @interface CachedCompatChangeId{}

    /**
     * Mapping from CACHED_COMPAT_CHANGE_* to the actual compat change id.
     */
    static final long[] CACHED_COMPAT_CHANGE_IDS_MAPPING = new long[] {
        PROCESS_CAPABILITY_CHANGE_ID,
        CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID,
        USE_SHORT_FGS_USAGE_INTERACTION_TIME,
    };

    private final PlatformCompat mPlatformCompat;
    private final IPlatformCompat mIPlatformCompatProxy;
    private final LongSparseArray<CacheItem> mCaches = new LongSparseArray<>();
    private final boolean mCacheEnabled;

    private static PlatformCompatCache sPlatformCompatCache;

    private PlatformCompatCache(long[] compatChanges) {
        IBinder b = ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE);
        if (b instanceof PlatformCompat) {
            mPlatformCompat = (PlatformCompat) ServiceManager.getService(
                    Context.PLATFORM_COMPAT_SERVICE);
            for (long changeId: compatChanges) {
                mCaches.put(changeId, new CacheItem(mPlatformCompat, changeId));
            }
            mIPlatformCompatProxy = null;
            mCacheEnabled = true;
        } else {
            // we are in UT where the platform_compat is not running within the same process
            mIPlatformCompatProxy = IPlatformCompat.Stub.asInterface(b);
            mPlatformCompat = null;
            mCacheEnabled = false;
        }
    }

    static PlatformCompatCache getInstance() {
        if (sPlatformCompatCache == null) {
            sPlatformCompatCache = new PlatformCompatCache(new long[] {
                PROCESS_CAPABILITY_CHANGE_ID,
                CAMERA_MICROPHONE_CAPABILITY_CHANGE_ID,
                USE_SHORT_FGS_USAGE_INTERACTION_TIME,
            });
        }
        return sPlatformCompatCache;
    }

    private boolean isChangeEnabled(long changeId, ApplicationInfo app, boolean defaultValue) {
        try {
            return mCacheEnabled ? mCaches.get(changeId).isChangeEnabled(app)
                    : mIPlatformCompatProxy.isChangeEnabled(changeId, app);
        } catch (RemoteException e) {
            Slog.w(TAG, "Error reading platform compat change " + changeId, e);
            return defaultValue;
        }
    }

    /**
     * @return If the given cached compat change id is enabled.
     */
    static boolean isChangeEnabled(@CachedCompatChangeId int cachedCompatChangeId,
            ApplicationInfo app, boolean defaultValue) {
        return getInstance().isChangeEnabled(
                CACHED_COMPAT_CHANGE_IDS_MAPPING[cachedCompatChangeId], app, defaultValue);
    }

    /**
     * Invalidate the cache for the given app.
     */
    void invalidate(ApplicationInfo app) {
        for (int i = mCaches.size() - 1; i >= 0; i--) {
            mCaches.valueAt(i).invalidate(app);
        }
    }

    /**
     * Update the cache due to application info changes.
     */
    void onApplicationInfoChanged(ApplicationInfo app) {
        for (int i = mCaches.size() - 1; i >= 0; i--) {
            mCaches.valueAt(i).onApplicationInfoChanged(app);
        }
    }

    static class CacheItem implements CompatChange.ChangeListener {
        private final PlatformCompat mPlatformCompat;
        private final long mChangeId;
        private final Object mLock = new Object();

        private final ArrayMap<String, Pair<Boolean, WeakReference<ApplicationInfo>>> mCache =
                new ArrayMap<>();

        CacheItem(PlatformCompat platformCompat, long changeId) {
            mPlatformCompat = platformCompat;
            mChangeId = changeId;
            mPlatformCompat.registerListener(changeId, this);
        }

        boolean isChangeEnabled(ApplicationInfo app) {
            synchronized (mLock) {
                final int index = mCache.indexOfKey(app.packageName);
                Pair<Boolean, WeakReference<ApplicationInfo>> p;
                if (index < 0) {
                    return fetchLocked(app, index);
                }
                p = mCache.valueAt(index);
                if (p.second.get() == app) {
                    return p.first;
                }
                // Cache is invalid, regenerate it
                return fetchLocked(app, index);
            }
        }

        void invalidate(ApplicationInfo app) {
            synchronized (mLock) {
                mCache.remove(app.packageName);
            }
        }

        @GuardedBy("mLock")
        boolean fetchLocked(ApplicationInfo app, int index) {
            final Pair<Boolean, WeakReference<ApplicationInfo>> p = new Pair<>(
                    mPlatformCompat.isChangeEnabledInternalNoLogging(mChangeId, app),
                    new WeakReference<>(app));
            if (index >= 0) {
                mCache.setValueAt(index, p);
            } else {
                mCache.put(app.packageName, p);
            }
            return p.first;
        }

        void onApplicationInfoChanged(ApplicationInfo app) {
            synchronized (mLock) {
                final int index = mCache.indexOfKey(app.packageName);
                if (index >= 0) {
                    fetchLocked(app, index);
                }
            }
        }

        @Override
        public void onCompatChange(String packageName) {
            synchronized (mLock) {
                final int index = mCache.indexOfKey(packageName);
                if (index >= 0) {
                    final ApplicationInfo app = mCache.valueAt(index).second.get();
                    if (app != null) {
                        fetchLocked(app, index);
                    } else {
                        mCache.removeAt(index);
                    }
                }
            }
        }
    }
}
