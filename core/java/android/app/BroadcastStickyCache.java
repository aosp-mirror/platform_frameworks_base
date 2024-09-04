/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.app;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemProperties;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;

/** @hide */
public class BroadcastStickyCache {

    @GuardedBy("sCachedStickyBroadcasts")
    private static final ArrayList<CachedStickyBroadcast> sCachedStickyBroadcasts =
            new ArrayList<>();

    @GuardedBy("sCachedPropertyHandles")
    private static final ArrayMap<String, SystemProperties.Handle> sCachedPropertyHandles =
            new ArrayMap<>();

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static boolean useCache(@Nullable IntentFilter filter) {
        if (!Flags.useStickyBcastCache()) {
            return false;
        }
        if (filter == null || filter.safeCountActions() != 1) {
            return false;
        }
        synchronized (sCachedStickyBroadcasts) {
            final CachedStickyBroadcast cachedStickyBroadcast = getValueUncheckedLocked(filter);
            if (cachedStickyBroadcast == null) {
                return false;
            }
            final long version = cachedStickyBroadcast.propertyHandle.getLong(-1 /* def */);
            return version > 0 && cachedStickyBroadcast.version == version;
        }
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static void add(@Nullable IntentFilter filter, @Nullable Intent intent) {
        if (!Flags.useStickyBcastCache()) {
            return;
        }
        if (filter == null || filter.safeCountActions() != 1) {
            return;
        }
        synchronized (sCachedStickyBroadcasts) {
            CachedStickyBroadcast cachedStickyBroadcast = getValueUncheckedLocked(filter);
            if (cachedStickyBroadcast == null) {
                final String key = getKey(filter.getAction(0));
                final SystemProperties.Handle handle = SystemProperties.find(key);
                final long version = handle == null ? -1 : handle.getLong(-1 /* def */);
                if (version == -1) {
                    return;
                }
                cachedStickyBroadcast = new CachedStickyBroadcast(filter, handle);
                sCachedStickyBroadcasts.add(cachedStickyBroadcast);
                cachedStickyBroadcast.intent = intent;
                cachedStickyBroadcast.version = version;
            } else {
                cachedStickyBroadcast.intent = intent;
                cachedStickyBroadcast.version = cachedStickyBroadcast.propertyHandle
                        .getLong(-1 /* def */);
            }
        }
    }

    @VisibleForTesting
    @NonNull
    public static String getKey(@NonNull String action) {
        return "cache_key.system_server.sticky_bcast." + action;
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Nullable
    public static Intent getIntentUnchecked(@NonNull IntentFilter filter) {
        synchronized (sCachedStickyBroadcasts) {
            final CachedStickyBroadcast cachedStickyBroadcast = getValueUncheckedLocked(filter);
            return cachedStickyBroadcast.intent;
        }
    }

    @GuardedBy("sCachedStickyBroadcasts")
    @Nullable
    private static CachedStickyBroadcast getValueUncheckedLocked(@NonNull IntentFilter filter) {
        for (int i = sCachedStickyBroadcasts.size() - 1; i >= 0; --i) {
            final CachedStickyBroadcast cachedStickyBroadcast = sCachedStickyBroadcasts.get(i);
            if (IntentFilter.filterEquals(filter, cachedStickyBroadcast.filter)) {
                return cachedStickyBroadcast;
            }
        }
        return null;
    }

    public static void incrementVersion(@NonNull String action) {
        if (!Flags.useStickyBcastCache()) {
            return;
        }
        final String key = getKey(action);
        synchronized (sCachedPropertyHandles) {
            SystemProperties.Handle handle = sCachedPropertyHandles.get(key);
            final long version;
            if (handle == null) {
                handle = SystemProperties.find(key);
                if (handle != null) {
                    sCachedPropertyHandles.put(key, handle);
                }
            }
            version = handle == null ? 0 : handle.getLong(0 /* def */);
            SystemProperties.set(key, String.valueOf(version + 1));
            if (handle == null) {
                sCachedPropertyHandles.put(key, SystemProperties.find(key));
            }
        }
    }

    public static void incrementVersionIfExists(@NonNull String action) {
        if (!Flags.useStickyBcastCache()) {
            return;
        }
        final String key = getKey(action);
        synchronized (sCachedPropertyHandles) {
            final SystemProperties.Handle handle = sCachedPropertyHandles.get(key);
            if (handle == null) {
                return;
            }
            final long version = handle.getLong(0 /* def */);
            SystemProperties.set(key, String.valueOf(version + 1));
        }
    }

    @VisibleForTesting
    public static void clearForTest() {
        synchronized (sCachedStickyBroadcasts) {
            sCachedStickyBroadcasts.clear();
        }
        synchronized (sCachedPropertyHandles) {
            sCachedPropertyHandles.clear();
        }
    }

    private static final class CachedStickyBroadcast {
        @NonNull public final IntentFilter filter;
        @Nullable public Intent intent;
        @IntRange(from = 0) public long version;
        @NonNull public final SystemProperties.Handle propertyHandle;

        CachedStickyBroadcast(@NonNull IntentFilter filter,
                @NonNull SystemProperties.Handle propertyHandle) {
            this.filter = filter;
            this.propertyHandle = propertyHandle;
        }
    }
}
