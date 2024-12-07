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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context.RegisterReceiverFlags;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.TetheringManager;
import android.net.nsd.NsdManager;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.IpcDataCache;
import android.os.IpcDataCache.Config;
import android.os.UpdateLock;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;
import android.view.WindowManagerPolicyConstants;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

/** @hide */
public class BroadcastStickyCache {

    @VisibleForTesting
    public static final String[] STICKY_BROADCAST_ACTIONS = {
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

    @VisibleForTesting
    public static final ArrayMap<String, String> sActionApiNameMap = new ArrayMap<>();

    @GuardedBy("BroadcastStickyCache.class")
    private static final ArrayMap<String, IpcDataCache.Config> sActionConfigMap = new ArrayMap<>();

    @GuardedBy("BroadcastStickyCache.class")
    private static final ArrayMap<StickyBroadcastFilter, IpcDataCache<Void, Intent>>
            sFilterCacheMap = new ArrayMap<>();

    static {
        sActionApiNameMap.put(AudioManager.ACTION_HDMI_AUDIO_PLUG, "hdmi_audio_plug");
        sActionApiNameMap.put(AudioManager.ACTION_HEADSET_PLUG, "headset_plug");
        sActionApiNameMap.put(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED,
                "sco_audio_state_changed");
        sActionApiNameMap.put(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED,
                "action_sco_audio_state_updated");
        sActionApiNameMap.put(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION,
                "internal_ringer_mode_changed_action");
        sActionApiNameMap.put(AudioManager.RINGER_MODE_CHANGED_ACTION,
                "ringer_mode_changed");
        sActionApiNameMap.put(ConnectivityManager.CONNECTIVITY_ACTION,
                "connectivity_change");
        sActionApiNameMap.put(Intent.ACTION_BATTERY_CHANGED, "battery_changed");
        sActionApiNameMap.put(Intent.ACTION_DEVICE_STORAGE_FULL, "device_storage_full");
        sActionApiNameMap.put(Intent.ACTION_DEVICE_STORAGE_LOW, "device_storage_low");
        sActionApiNameMap.put(Intent.ACTION_SIM_STATE_CHANGED, "sim_state_changed");
        sActionApiNameMap.put(NsdManager.ACTION_NSD_STATE_CHANGED, "nsd_state_changed");
        sActionApiNameMap.put(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED,
                "service_providers_updated");
        sActionApiNameMap.put(TetheringManager.ACTION_TETHER_STATE_CHANGED,
                "tether_state_changed");
        sActionApiNameMap.put(UpdateLock.UPDATE_LOCK_CHANGED, "update_lock_changed");
        sActionApiNameMap.put(UsbManager.ACTION_USB_STATE, "usb_state");
        sActionApiNameMap.put(WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED,
                "wifi_scan_availability_changed");
        sActionApiNameMap.put(WifiManager.NETWORK_STATE_CHANGED_ACTION,
                "network_state_change");
        sActionApiNameMap.put(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION,
                "supplicant_state_change");
        sActionApiNameMap.put(WifiManager.WIFI_STATE_CHANGED_ACTION, "wifi_state_changed");
        sActionApiNameMap.put(
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION, "wifi_p2p_state_changed");
        sActionApiNameMap.put(
                WindowManagerPolicyConstants.ACTION_HDMI_PLUGGED, "hdmi_plugged");
        sActionApiNameMap.put(
                "android.net.conn.INET_CONDITION_ACTION", "inet_condition_action");
    }

    /**
     * Checks whether we can use caching for the given filter.
     */
    public static boolean useCache(@Nullable IntentFilter filter) {
        return Flags.useStickyBcastCache()
                && filter != null
                && filter.safeCountActions() == 1
                && ArrayUtils.contains(STICKY_BROADCAST_ACTIONS, filter.getAction(0));
    }

    public static void invalidateCache(@NonNull String action) {
        if (!Flags.useStickyBcastCache()
                || !ArrayUtils.contains(STICKY_BROADCAST_ACTIONS, action)) {
            return;
        }
        IpcDataCache.invalidateCache(IpcDataCache.MODULE_SYSTEM,
                sActionApiNameMap.get(action));
    }

    public static void invalidateAllCaches() {
        for (int i = sActionApiNameMap.size() - 1; i >= 0; i--) {
            IpcDataCache.invalidateCache(IpcDataCache.MODULE_SYSTEM,
                    sActionApiNameMap.valueAt(i));
        }
    }

    /**
     * Returns the cached {@link Intent} based on the filter, if exits otherwise
     * fetches the value from the service.
     */
    @Nullable
    public static Intent getIntent(
            @NonNull IApplicationThread applicationThread,
            @NonNull String mBasePackageName,
            @Nullable String attributionTag,
            @NonNull IntentFilter filter,
            @Nullable String broadcastPermission,
            @UserIdInt int userId,
            @RegisterReceiverFlags int flags) {
        IpcDataCache<Void, Intent> intentDataCache;

        synchronized (BroadcastStickyCache.class) {
            intentDataCache = findIpcDataCache(filter);

            if (intentDataCache == null) {
                final String action = filter.getAction(0);
                final StickyBroadcastFilter stickyBroadcastFilter =
                        new StickyBroadcastFilter(filter, action);
                final Config config = getConfig(action);

                intentDataCache =
                        new IpcDataCache<>(config,
                                (query) -> ActivityManager.getService().registerReceiverWithFeature(
                                        applicationThread,
                                        mBasePackageName,
                                        attributionTag,
                                        /* receiverId= */ "null",
                                        /* receiver= */ null,
                                        filter,
                                        broadcastPermission,
                                        userId,
                                        flags));
                sFilterCacheMap.put(stickyBroadcastFilter, intentDataCache);
            }
        }
        return intentDataCache.query(null);
    }

    @VisibleForTesting
    public static void clearCacheForTest() {
        synchronized (BroadcastStickyCache.class) {
            sFilterCacheMap.clear();
        }
    }

    @Nullable
    @GuardedBy("BroadcastStickyCache.class")
    private static IpcDataCache<Void, Intent> findIpcDataCache(
            @NonNull IntentFilter filter) {
        for (int i = sFilterCacheMap.size() - 1; i >= 0; i--) {
            StickyBroadcastFilter existingFilter = sFilterCacheMap.keyAt(i);
            if (filter.getAction(0).equals(existingFilter.action())
                    && IntentFilter.filterEquals(existingFilter.filter(), filter)) {
                return sFilterCacheMap.valueAt(i);
            }
        }
        return null;
    }

    @NonNull
    @GuardedBy("BroadcastStickyCache.class")
    private static IpcDataCache.Config getConfig(@NonNull String action) {
        if (!sActionConfigMap.containsKey(action)) {
            // We only need 1 entry per cache but just to be on the safer side we are choosing 32
            // although we don't expect more than 1.
            sActionConfigMap.put(action,
                    new Config(32, IpcDataCache.MODULE_SYSTEM,
                            sActionApiNameMap.get(action)).cacheNulls(true));
        }

        return sActionConfigMap.get(action);
    }

    @VisibleForTesting
    private record StickyBroadcastFilter(@NonNull IntentFilter filter, @NonNull String action) {
    }
}
