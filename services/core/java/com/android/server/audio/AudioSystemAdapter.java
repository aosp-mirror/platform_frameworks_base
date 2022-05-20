/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides an adapter to access functionality of the android.media.AudioSystem class for device
 * related functionality.
 * Use the "real" AudioSystem through the default adapter.
 * Use the "always ok" adapter to avoid dealing with the APM behaviors during a test.
 */
public class AudioSystemAdapter implements AudioSystem.RoutingUpdateCallback,
        AudioSystem.VolumeRangeInitRequestCallback {

    private static final String TAG = "AudioSystemAdapter";

    // initialized in factory getDefaultAdapter()
    private static AudioSystemAdapter sSingletonDefaultAdapter;

    /**
     * should be false by default unless enabling measurements of method call counts and time spent
     * in measured methods
     */
    private static final boolean ENABLE_GETDEVICES_STATS = false;
    private static final int NB_MEASUREMENTS = 1;
    private static final int METHOD_GETDEVICESFORATTRIBUTES = 0;
    private long[] mMethodTimeNs;
    private int[] mMethodCallCounter;
    private String[] mMethodNames = {"getDevicesForAttributes"};

    private static final boolean USE_CACHE_FOR_GETDEVICES = true;
    private ConcurrentHashMap<Pair<AudioAttributes, Boolean>, ArrayList<AudioDeviceAttributes>>
            mDevicesForAttrCache;
    private int[] mMethodCacheHit;
    private static final Object sRoutingListenerLock = new Object();
    @GuardedBy("sRoutingListenerLock")
    private static @Nullable OnRoutingUpdatedListener sRoutingListener;
    private static final Object sVolRangeInitReqListenerLock = new Object();
    @GuardedBy("sVolRangeInitReqListenerLock")
    private static @Nullable OnVolRangeInitRequestListener sVolRangeInitReqListener;

    /**
     * should be false except when trying to debug caching errors. When true, the value retrieved
     * from the cache will be compared against the real queried value, which defeats the purpose of
     * the cache in terms of performance.
     */
    private static final boolean DEBUG_CACHE = false;

    /**
     * Implementation of AudioSystem.RoutingUpdateCallback
     */
    @Override
    public void onRoutingUpdated() {
        if (DEBUG_CACHE) {
            Log.d(TAG, "---- onRoutingUpdated (from native) ----------");
        }
        invalidateRoutingCache();
        final OnRoutingUpdatedListener listener;
        synchronized (sRoutingListenerLock) {
            listener = sRoutingListener;
        }
        if (listener != null) {
            listener.onRoutingUpdatedFromNative();
        }
    }

    interface OnRoutingUpdatedListener {
        void onRoutingUpdatedFromNative();
    }

    static void setRoutingListener(@Nullable OnRoutingUpdatedListener listener) {
        synchronized (sRoutingListenerLock) {
            sRoutingListener = listener;
        }
    }

    /**
     * Implementation of AudioSystem.VolumeRangeInitRequestCallback
     */
    @Override
    public void onVolumeRangeInitializationRequested() {
        final OnVolRangeInitRequestListener listener;
        synchronized (sVolRangeInitReqListenerLock) {
            listener = sVolRangeInitReqListener;
        }
        if (listener != null) {
            listener.onVolumeRangeInitRequestFromNative();
        }
    }

    interface OnVolRangeInitRequestListener {
        void onVolumeRangeInitRequestFromNative();
    }

    static void setVolRangeInitReqListener(@Nullable OnVolRangeInitRequestListener listener) {
        synchronized (sVolRangeInitReqListenerLock) {
            sVolRangeInitReqListener = listener;
        }
    }

    /**
     * Create a wrapper around the {@link AudioSystem} static methods, all functions are directly
     * forwarded to the AudioSystem class.
     * @return an adapter around AudioSystem
     */
    static final synchronized @NonNull AudioSystemAdapter getDefaultAdapter() {
        if (sSingletonDefaultAdapter == null) {
            sSingletonDefaultAdapter = new AudioSystemAdapter();
            AudioSystem.setRoutingCallback(sSingletonDefaultAdapter);
            AudioSystem.setVolumeRangeInitRequestCallback(sSingletonDefaultAdapter);
            if (USE_CACHE_FOR_GETDEVICES) {
                sSingletonDefaultAdapter.mDevicesForAttrCache =
                        new ConcurrentHashMap<>(AudioSystem.getNumStreamTypes());
                sSingletonDefaultAdapter.mMethodCacheHit = new int[NB_MEASUREMENTS];
            }
            if (ENABLE_GETDEVICES_STATS) {
                sSingletonDefaultAdapter.mMethodCallCounter = new int[NB_MEASUREMENTS];
                sSingletonDefaultAdapter.mMethodTimeNs = new long[NB_MEASUREMENTS];
            }
        }
        return sSingletonDefaultAdapter;
    }

    private void invalidateRoutingCache() {
        if (DEBUG_CACHE) {
            Log.d(TAG, "---- clearing cache ----------");
        }
        if (mDevicesForAttrCache != null) {
            synchronized (mDevicesForAttrCache) {
                mDevicesForAttrCache.clear();
            }
        }
    }

    /**
     * Same as {@link AudioSystem#getDevicesForAttributes(AudioAttributes)}
     * @param attributes the attributes for which the routing is queried
     * @return the devices that the stream with the given attributes would be routed to
     */
    public @NonNull ArrayList<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes, boolean forVolume) {
        if (!ENABLE_GETDEVICES_STATS) {
            return getDevicesForAttributesImpl(attributes, forVolume);
        }
        mMethodCallCounter[METHOD_GETDEVICESFORATTRIBUTES]++;
        final long startTime = SystemClock.uptimeNanos();
        final ArrayList<AudioDeviceAttributes> res = getDevicesForAttributesImpl(
                attributes, forVolume);
        mMethodTimeNs[METHOD_GETDEVICESFORATTRIBUTES] += SystemClock.uptimeNanos() - startTime;
        return res;
    }

    private @NonNull ArrayList<AudioDeviceAttributes> getDevicesForAttributesImpl(
            @NonNull AudioAttributes attributes, boolean forVolume) {
        if (USE_CACHE_FOR_GETDEVICES) {
            ArrayList<AudioDeviceAttributes> res;
            final Pair<AudioAttributes, Boolean> key = new Pair(attributes, forVolume);
            synchronized (mDevicesForAttrCache) {
                res = mDevicesForAttrCache.get(key);
                if (res == null) {
                    // result from AudioSystem guaranteed non-null, but could be invalid
                    // if there is a failure to talk to APM
                    res = AudioSystem.getDevicesForAttributes(attributes, forVolume);
                    if (res.size() > 1 && res.get(0) != null
                            && res.get(0).getInternalType() == AudioSystem.DEVICE_NONE) {
                        Log.e(TAG, "unable to get devices for " + attributes);
                        // return now, do not put invalid value in cache
                        return res;
                    }
                    mDevicesForAttrCache.put(key, res);
                    if (DEBUG_CACHE) {
                        Log.d(TAG, mMethodNames[METHOD_GETDEVICESFORATTRIBUTES]
                                + attrDeviceToDebugString(attributes, res));
                    }
                    return res;
                }
                // cache hit
                mMethodCacheHit[METHOD_GETDEVICESFORATTRIBUTES]++;
                if (DEBUG_CACHE) {
                    final ArrayList<AudioDeviceAttributes> real =
                            AudioSystem.getDevicesForAttributes(attributes, forVolume);
                    if (res.equals(real)) {
                        Log.d(TAG, mMethodNames[METHOD_GETDEVICESFORATTRIBUTES]
                                + attrDeviceToDebugString(attributes, res) + " CACHE");
                    } else {
                        Log.e(TAG, mMethodNames[METHOD_GETDEVICESFORATTRIBUTES]
                                + attrDeviceToDebugString(attributes, res)
                                + " CACHE ERROR real:" + attrDeviceToDebugString(attributes, real));
                    }
                }
            }
            return res;
        }
        // not using cache
        return AudioSystem.getDevicesForAttributes(attributes, forVolume);
    }

    private static String attrDeviceToDebugString(@NonNull AudioAttributes attr,
            @NonNull List<AudioDeviceAttributes> devices) {
        return " attrUsage=" + attr.getSystemUsage() + " "
                + AudioSystem.deviceSetToString(AudioSystem.generateAudioDeviceTypesSet(devices));
    }

    /**
     * Same as {@link AudioSystem#setDeviceConnectionState(AudioDeviceAttributes, int, int)}
     * @param attributes
     * @param state
     * @param codecFormat
     * @return
     */
    public int setDeviceConnectionState(AudioDeviceAttributes attributes, int state,
            int codecFormat) {
        invalidateRoutingCache();
        return AudioSystem.setDeviceConnectionState(attributes, state, codecFormat);
    }

    /**
     * Same as {@link AudioSystem#getDeviceConnectionState(int, String)}
     * @param device
     * @param deviceAddress
     * @return
     */
    public int getDeviceConnectionState(int device, String deviceAddress) {
        return AudioSystem.getDeviceConnectionState(device, deviceAddress);
    }

    /**
     * Same as {@link AudioSystem#handleDeviceConfigChange(int, String, String, int)}
     * @param device
     * @param deviceAddress
     * @param deviceName
     * @param codecFormat
     * @return
     */
    public int handleDeviceConfigChange(int device, String deviceAddress,
                                               String deviceName, int codecFormat) {
        invalidateRoutingCache();
        return AudioSystem.handleDeviceConfigChange(device, deviceAddress, deviceName,
                codecFormat);
    }

    /**
     * Same as {@link AudioSystem#setDevicesRoleForStrategy(int, int, List)}
     * @param strategy
     * @param role
     * @param devices
     * @return
     */
    public int setDevicesRoleForStrategy(int strategy, int role,
                                         @NonNull List<AudioDeviceAttributes> devices) {
        invalidateRoutingCache();
        return AudioSystem.setDevicesRoleForStrategy(strategy, role, devices);
    }

    /**
     * Same as {@link AudioSystem#removeDevicesRoleForStrategy(int, int)}
     * @param strategy
     * @param role
     * @return
     */
    public int removeDevicesRoleForStrategy(int strategy, int role) {
        invalidateRoutingCache();
        return AudioSystem.removeDevicesRoleForStrategy(strategy, role);
    }

    /**
     * Same as (@link AudioSystem#setDevicesRoleForCapturePreset(int, List))
     * @param capturePreset
     * @param role
     * @param devices
     * @return
     */
    public int setDevicesRoleForCapturePreset(int capturePreset, int role,
                                              @NonNull List<AudioDeviceAttributes> devices) {
        invalidateRoutingCache();
        return AudioSystem.setDevicesRoleForCapturePreset(capturePreset, role, devices);
    }

    /**
     * Same as {@link AudioSystem#removeDevicesRoleForCapturePreset(int, int, int[], String[])}
     * @param capturePreset
     * @param role
     * @param devicesToRemove
     * @return
     */
    public int removeDevicesRoleForCapturePreset(
            int capturePreset, int role, @NonNull List<AudioDeviceAttributes> devicesToRemove) {
        invalidateRoutingCache();
        return AudioSystem.removeDevicesRoleForCapturePreset(capturePreset, role, devicesToRemove);
    }

    /**
     * Same as {@link AudioSystem#}
     * @param capturePreset
     * @param role
     * @return
     */
    public int clearDevicesRoleForCapturePreset(int capturePreset, int role) {
        invalidateRoutingCache();
        return AudioSystem.clearDevicesRoleForCapturePreset(capturePreset, role);
    }

    /**
     * Same as {@link AudioSystem#setParameters(String)}
     * @param keyValuePairs
     * @return
     */
    public int setParameters(String keyValuePairs) {
        return AudioSystem.setParameters(keyValuePairs);
    }

    /**
     * Same as {@link AudioSystem#isMicrophoneMuted()}}
     * Checks whether the microphone mute is on or off.
     * @return true if microphone is muted, false if it's not
     */
    public boolean isMicrophoneMuted() {
        return AudioSystem.isMicrophoneMuted();
    }

    /**
     * Same as {@link AudioSystem#muteMicrophone(boolean)}
     * Sets the microphone mute on or off.
     *
     * @param on set <var>true</var> to mute the microphone;
     *           <var>false</var> to turn mute off
     * @return command completion status see AUDIO_STATUS_OK, see AUDIO_STATUS_ERROR
     */
    public int muteMicrophone(boolean on) {
        return AudioSystem.muteMicrophone(on);
    }

    /**
     * Same as {@link AudioSystem#setCurrentImeUid(int)}
     * Communicate UID of current InputMethodService to audio policy service.
     */
    public int setCurrentImeUid(int uid) {
        return AudioSystem.setCurrentImeUid(uid);
    }

    /**
     * Same as {@link AudioSystem#isStreamActive(int, int)}
     */
    public boolean isStreamActive(int stream, int inPastMs) {
        return AudioSystem.isStreamActive(stream, inPastMs);
    }

    /**
     * Same as {@link AudioSystem#isStreamActiveRemotely(int, int)}
     * @param stream
     * @param inPastMs
     * @return
     */
    public boolean isStreamActiveRemotely(int stream, int inPastMs) {
        return AudioSystem.isStreamActiveRemotely(stream, inPastMs);
    }

    /**
     * Same as {@link AudioSystem#setPhoneState(int, int)}
     * @param state
     * @param uid
     * @return
     */
    public int setPhoneState(int state, int uid) {
        invalidateRoutingCache();
        return AudioSystem.setPhoneState(state, uid);
    }

    /**
     * Same as {@link AudioSystem#setAllowedCapturePolicy(int, int)}
     * @param uid
     * @param flags
     * @return
     */
    public int setAllowedCapturePolicy(int uid, int flags) {
        return AudioSystem.setAllowedCapturePolicy(uid, flags);
    }

    /**
     * Same as {@link AudioSystem#setForceUse(int, int)}
     * @param usage
     * @param config
     * @return
     */
    public int setForceUse(int usage, int config) {
        invalidateRoutingCache();
        return AudioSystem.setForceUse(usage, config);
    }

    /**
     * Same as {@link AudioSystem#getForceUse(int)}
     * @param usage
     * @return
     */
    public int getForceUse(int usage) {
        return AudioSystem.getForceUse(usage);
    }

    /**
     * Same as {@link AudioSystem#registerPolicyMixes(ArrayList, boolean)}
     * @param mixes
     * @param register
     * @return
     */
    public int registerPolicyMixes(ArrayList<AudioMix> mixes, boolean register) {
        invalidateRoutingCache();
        return AudioSystem.registerPolicyMixes(mixes, register);
    }

    /**
     * Same as {@link AudioSystem#setUidDeviceAffinities(int, int[], String[])}
     * @param uid
     * @param types
     * @param addresses
     * @return
     */
    public int setUidDeviceAffinities(int uid, @NonNull int[] types,  @NonNull String[] addresses) {
        invalidateRoutingCache();
        return AudioSystem.setUidDeviceAffinities(uid, types, addresses);
    }

    /**
     * Same as {@link AudioSystem#removeUidDeviceAffinities(int)}
     * @param uid
     * @return
     */
    public int removeUidDeviceAffinities(int uid) {
        invalidateRoutingCache();
        return AudioSystem.removeUidDeviceAffinities(uid);
    }

    /**
     * Same as {@link AudioSystem#setUserIdDeviceAffinities(int, int[], String[])}
     * @param userId
     * @param types
     * @param addresses
     * @return
     */
    public int setUserIdDeviceAffinities(int userId, @NonNull int[] types,
            @NonNull String[] addresses) {
        invalidateRoutingCache();
        return AudioSystem.setUserIdDeviceAffinities(userId, types, addresses);
    }

    /**
     * Same as {@link AudioSystem#removeUserIdDeviceAffinities(int)}
     * @param userId
     * @return
     */
    public int removeUserIdDeviceAffinities(int userId) {
        invalidateRoutingCache();
        return AudioSystem.removeUserIdDeviceAffinities(userId);
    }

    /**
     * Part of AudioService dump
     * @param pw
     */
    public void dump(PrintWriter pw) {
        pw.println("\nAudioSystemAdapter:");
        pw.println(" mDevicesForAttrCache:");
        if (mDevicesForAttrCache != null) {
            for (Map.Entry<Pair<AudioAttributes, Boolean>, ArrayList<AudioDeviceAttributes>>
                    entry : mDevicesForAttrCache.entrySet()) {
                final AudioAttributes attributes = entry.getKey().first;
                try {
                    final int stream = attributes.getVolumeControlStream();
                    pw.println("\t" + attributes + " forVolume: " + entry.getKey().second
                            + " stream: "
                            + AudioSystem.STREAM_NAMES[stream] + "(" + stream + ")");
                    for (AudioDeviceAttributes devAttr : entry.getValue()) {
                        pw.println("\t\t" + devAttr);
                    }
                } catch (IllegalArgumentException e) {
                    // dump could fail if attributes do not map to a stream.
                    pw.println("\t dump failed for attributes: " + attributes);
                    Log.e(TAG, "dump failed", e);
                }
            }
        }

        if (!ENABLE_GETDEVICES_STATS) {
            // only stats in the rest of this dump
            return;
        }
        for (int i = 0; i < NB_MEASUREMENTS; i++) {
            pw.println(mMethodNames[i]
                    + ": counter=" + mMethodCallCounter[i]
                    + " time(ms)=" + (mMethodTimeNs[i] / 1E6)
                    + (USE_CACHE_FOR_GETDEVICES
                        ? (" FScacheHit=" + mMethodCacheHit[METHOD_GETDEVICESFORATTRIBUTES])
                        : ""));
        }
        pw.println("\n");
    }
}
