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
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.os.SystemClock;
import android.util.Log;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides an adapter to access functionality of the android.media.AudioSystem class for device
 * related functionality.
 * Use the "real" AudioSystem through the default adapter.
 * Use the "always ok" adapter to avoid dealing with the APM behaviors during a test.
 */
public class AudioSystemAdapter implements AudioSystem.RoutingUpdateCallback {

    private static final String TAG = "AudioSystemAdapter";

    // initialized in factory getDefaultAdapter()
    private static AudioSystemAdapter sSingletonDefaultAdapter;

    /**
     * should be false by default unless enabling measurements of method call counts and time spent
     * in measured methods
     */
    private static final boolean ENABLE_GETDEVICES_STATS = false;
    private static final int NB_MEASUREMENTS = 2;
    private static final int METHOD_GETDEVICESFORSTREAM = 0;
    private static final int METHOD_GETDEVICESFORATTRIBUTES = 1;
    private long[] mMethodTimeNs;
    private int[] mMethodCallCounter;
    private String[] mMethodNames = {"getDevicesForStream", "getDevicesForAttributes"};

    private static final boolean USE_CACHE_FOR_GETDEVICES = true;
    private ConcurrentHashMap<Integer, Integer> mDevicesForStreamCache;
    private ConcurrentHashMap<AudioAttributes, ArrayList<AudioDeviceAttributes>>
            mDevicesForAttrCache;
    private int[] mMethodCacheHit;

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
            if (USE_CACHE_FOR_GETDEVICES) {
                sSingletonDefaultAdapter.mDevicesForStreamCache =
                        new ConcurrentHashMap<>(AudioSystem.getNumStreamTypes());
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
        if (mDevicesForStreamCache != null) {
            synchronized (mDevicesForStreamCache) {
                mDevicesForStreamCache.clear();
            }
        }
        if (mDevicesForAttrCache != null) {
            synchronized (mDevicesForAttrCache) {
                mDevicesForAttrCache.clear();
            }
        }
    }

    /**
     * Same as {@link AudioSystem#getDevicesForStream(int)}
     * @param stream a valid stream type
     * @return a mask of device types
     */
    public int getDevicesForStream(int stream) {
        if (!ENABLE_GETDEVICES_STATS) {
            return getDevicesForStreamImpl(stream);
        }
        mMethodCallCounter[METHOD_GETDEVICESFORSTREAM]++;
        final long startTime = SystemClock.uptimeNanos();
        final int res = getDevicesForStreamImpl(stream);
        mMethodTimeNs[METHOD_GETDEVICESFORSTREAM] += SystemClock.uptimeNanos() - startTime;
        return res;
    }

    private int getDevicesForStreamImpl(int stream) {
        if (USE_CACHE_FOR_GETDEVICES) {
            Integer res;
            synchronized (mDevicesForStreamCache) {
                res = mDevicesForStreamCache.get(stream);
                if (res == null) {
                    res = AudioSystem.getDevicesForStream(stream);
                    mDevicesForStreamCache.put(stream, res);
                    if (DEBUG_CACHE) {
                        Log.d(TAG, mMethodNames[METHOD_GETDEVICESFORSTREAM]
                                + streamDeviceToDebugString(stream, res));
                    }
                    return res;
                }
                // cache hit
                mMethodCacheHit[METHOD_GETDEVICESFORSTREAM]++;
                if (DEBUG_CACHE) {
                    final int real = AudioSystem.getDevicesForStream(stream);
                    if (res == real) {
                        Log.d(TAG, mMethodNames[METHOD_GETDEVICESFORSTREAM]
                                + streamDeviceToDebugString(stream, res) + " CACHE");
                    } else {
                        Log.e(TAG, mMethodNames[METHOD_GETDEVICESFORSTREAM]
                                + streamDeviceToDebugString(stream, res)
                                + " CACHE ERROR real dev=0x" + Integer.toHexString(real));
                    }
                }
            }
            return res;
        }
        // not using cache
        return AudioSystem.getDevicesForStream(stream);
    }

    private static String streamDeviceToDebugString(int stream, int dev) {
        return " stream=" + stream + " dev=0x" + Integer.toHexString(dev);
    }

    /**
     * Same as {@link AudioSystem#getDevicesForAttributes(AudioAttributes)}
     * @param attributes the attributes for which the routing is queried
     * @return the devices that the stream with the given attributes would be routed to
     */
    public @NonNull ArrayList<AudioDeviceAttributes> getDevicesForAttributes(
            @NonNull AudioAttributes attributes) {
        if (!ENABLE_GETDEVICES_STATS) {
            return getDevicesForAttributesImpl(attributes);
        }
        mMethodCallCounter[METHOD_GETDEVICESFORATTRIBUTES]++;
        final long startTime = SystemClock.uptimeNanos();
        final ArrayList<AudioDeviceAttributes> res = getDevicesForAttributesImpl(attributes);
        mMethodTimeNs[METHOD_GETDEVICESFORATTRIBUTES] += SystemClock.uptimeNanos() - startTime;
        return res;
    }

    private @NonNull ArrayList<AudioDeviceAttributes> getDevicesForAttributesImpl(
            @NonNull AudioAttributes attributes) {
        if (USE_CACHE_FOR_GETDEVICES) {
            ArrayList<AudioDeviceAttributes> res;
            synchronized (mDevicesForAttrCache) {
                res = mDevicesForAttrCache.get(attributes);
                if (res == null) {
                    res = AudioSystem.getDevicesForAttributes(attributes);
                    mDevicesForAttrCache.put(attributes, res);
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
                            AudioSystem.getDevicesForAttributes(attributes);
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
        return AudioSystem.getDevicesForAttributes(attributes);
    }

    private static String attrDeviceToDebugString(@NonNull AudioAttributes attr,
            @NonNull ArrayList<AudioDeviceAttributes> devices) {
        String ds = " attrUsage=" + attr.getSystemUsage();
        for (AudioDeviceAttributes ada : devices) {
            ds = ds.concat(" dev=0x" + Integer.toHexString(ada.getInternalType()));
        }
        return ds;
    }

    /**
     * Same as {@link AudioSystem#setDeviceConnectionState(int, int, String, String, int)}
     * @param device
     * @param state
     * @param deviceAddress
     * @param deviceName
     * @param codecFormat
     * @return
     */
    public int setDeviceConnectionState(int device, int state, String deviceAddress,
                                        String deviceName, int codecFormat) {
        invalidateRoutingCache();
        return AudioSystem.setDeviceConnectionState(device, state, deviceAddress, deviceName,
                codecFormat);
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
     * Same as {@link AudioSystem#setHotwordDetectionServiceUid(int)}
     * Communicate UID of current HotwordDetectionService to audio policy service.
     */
    public int setHotwordDetectionServiceUid(int uid) {
        return AudioSystem.setHotwordDetectionServiceUid(uid);
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
        if (!ENABLE_GETDEVICES_STATS) {
            // only stats in this dump
            return;
        }
        pw.println("\nAudioSystemAdapter:");
        for (int i = 0; i < NB_MEASUREMENTS; i++) {
            pw.println(mMethodNames[i]
                    + ": counter=" + mMethodCallCounter[i]
                    + " time(ms)=" + (mMethodTimeNs[i] / 1E6)
                    + (USE_CACHE_FOR_GETDEVICES
                        ? (" FScacheHit=" + mMethodCacheHit[METHOD_GETDEVICESFORSTREAM]) : ""));
        }
        pw.println("\n");
    }
}
