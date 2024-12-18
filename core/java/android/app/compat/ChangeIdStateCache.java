/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.app.compat;

import android.annotation.NonNull;
import android.app.PropertyInvalidatedCache;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.compat.IPlatformCompat;

/**
 * Handles caching of calls to {@link com.android.internal.compat.IPlatformCompat}
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ChangeIdStateCache
        extends PropertyInvalidatedCache<ChangeIdStateQuery, Boolean> {

    private static final String CACHE_MODULE = PropertyInvalidatedCache.MODULE_SYSTEM;
    private static final String CACHE_API = "is_compat_change_enabled";

    private static final int MAX_ENTRIES = 2048;
    private static boolean sDisabled = getDefaultDisabled();
    private volatile IPlatformCompat mPlatformCompat;


    @android.ravenwood.annotation.RavenwoodReplace
    private static boolean getDefaultDisabled() {
        return false;
    }

    private static boolean getDefaultDisabled$ravenwood() {
        return true; // TODO(b/376676753) Disable the cache for now.
    }

    /** @hide */
    public ChangeIdStateCache() {
        super(new PropertyInvalidatedCache.Args(CACHE_MODULE)
                .maxEntries(MAX_ENTRIES)
                .isolateUids(false)
                .cacheNulls(false)
                .api(CACHE_API),
                CACHE_API, null);
    }

    /**
     * Disable cache.
     *
     * <p>Should only be used in unit tests.
     * @hide
     */
    public static void disable() {
        sDisabled = true;
    }

    /**
     * Invalidate the cache.
     *
     * <p>Can only be called by the system server process.
     * @hide
     */
    public static void invalidate() {
        if (!sDisabled) {
            PropertyInvalidatedCache.invalidateCache(CACHE_MODULE, CACHE_API);
        }
    }

    @NonNull
    IPlatformCompat getPlatformCompatService() {
        IPlatformCompat platformCompat = mPlatformCompat;
        if (platformCompat == null) {
            synchronized (this) {
                platformCompat = mPlatformCompat;
                if (platformCompat == null) {
                    platformCompat = IPlatformCompat.Stub.asInterface(
                        ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
                    if (platformCompat == null) {
                        throw new RuntimeException(
                            "Could not get PlatformCompatService instance!");
                    }
                    mPlatformCompat = platformCompat;
                }
            }
        }
        return platformCompat;
    }

    @Override
    public Boolean recompute(ChangeIdStateQuery query) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (query.type == ChangeIdStateQuery.QUERY_BY_PACKAGE_NAME) {
                return getPlatformCompatService().isChangeEnabledByPackageName(query.changeId,
                                                                   query.packageName,
                                                                   query.userId);
            } else if (query.type == ChangeIdStateQuery.QUERY_BY_UID) {
                return getPlatformCompatService().isChangeEnabledByUid(query.changeId, query.uid);
            } else {
                throw new IllegalArgumentException("Invalid query type: " + query.type);
            }
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
        throw new IllegalStateException("Could not recompute value!");
    }
}
