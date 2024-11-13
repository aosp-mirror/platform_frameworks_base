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
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.net.nsd.NsdManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.SystemProperties;
import android.os.UpdateLock;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;

/** @hide */
public class BroadcastStickyCache {

    private static final String[] CACHED_BROADCAST_ACTIONS = {
            AudioManager.ACTION_HDMI_AUDIO_PLUG,
            AudioManager.ACTION_HEADSET_PLUG,
            AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED,
            AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,
            AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION,
            AudioManager.RINGER_MODE_CHANGED_ACTION,
            ConnectivityManager.CONNECTIVITY_ACTION,
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_DEVICE_STORAGE_FULL,
            Intent.ACTION_DEVICE_STORAGE_LOW,
            Intent.ACTION_SIM_STATE_CHANGED,
            NsdManager.ACTION_NSD_STATE_CHANGED,
            TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED,
            TetheringManager.ACTION_TETHER_STATE_CHANGED,
            UpdateLock.UPDATE_LOCK_CHANGED,
            UsbManager.ACTION_USB_STATE,
            WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED,
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
            WifiManager.SUPPLICANT_STATE_CHANGED_ACTION,
            WifiManager.WIFI_STATE_CHANGED_ACTION,
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION,
            WindowManagerPolicyConstants.ACTION_HDMI_PLUGGED,
            "android.net.conn.INET_CONDITION_ACTION" // ConnectivityManager.INET_CONDITION_ACTION
    };

    @GuardedBy("sCachedStickyBroadcasts")
    private static final ArrayList<CachedStickyBroadcast> sCachedStickyBroadcasts =
            new ArrayList<>();

    @GuardedBy("sCachedPropertyHandles")
    private static final ArrayMap<String, SystemProperties.Handle> sCachedPropertyHandles =
            new ArrayMap<>();

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static boolean useCache(@Nullable IntentFilter filter) {
        if (!shouldCache(filter)) {
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
        if (!shouldCache(filter)) {
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

    private static boolean shouldCache(@Nullable IntentFilter filter) {
        if (!Flags.useStickyBcastCache()) {
            return false;
        }
        if (filter == null || filter.safeCountActions() != 1) {
            return false;
        }
        if (!ArrayUtils.contains(CACHED_BROADCAST_ACTIONS, filter.getAction(0))) {
            return false;
        }
        return true;
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
        if (!shouldIncrementVersion(action)) {
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
        if (!shouldIncrementVersion(action)) {
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

    private static boolean shouldIncrementVersion(@NonNull String action) {
        if (!Flags.useStickyBcastCache()) {
            return false;
        }
        if (!ArrayUtils.contains(CACHED_BROADCAST_ACTIONS, action)) {
            return false;
        }
        return true;
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
