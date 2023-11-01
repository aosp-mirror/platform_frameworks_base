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

package com.android.server.audio;

import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_CARKIT;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_HEADPHONES;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_HEARING_AID;
import static android.media.AudioManager.AUDIO_DEVICE_CATEGORY_WATCH;
import static android.media.AudioPlaybackConfiguration.PLAYER_DEVICEID_INVALID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.media.AudioDeviceInfo;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioSystem;
import android.media.ILoudnessCodecUpdatesDispatcher;
import android.media.LoudnessCodecInfo;
import android.media.permission.ClearCallingIdentityContext;
import android.media.permission.SafeCloseable;
import android.os.Binder;
import android.os.PersistableBundle;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Class to handle the updates in loudness parameters and responsible to generate parameters that
 * can be set directly on a MediaCodec.
 */
public class LoudnessCodecHelper {
    private static final String TAG = "AS.LoudnessCodecHelper";

    private static final boolean DEBUG = false;

    /**
     * Property containing a string to set for a custom built in speaker SPL range as defined by
     * CTA2075. The options that can be set are:
     *   - "small": for max SPL with test signal < 75 dB,
     *   - "medium": for max SPL with test signal between 70 and 90 dB,
     *   - "large": for max SPL with test signal > 85 dB.
     */
    private static final String SYSTEM_PROPERTY_SPEAKER_SPL_RANGE_SIZE =
            "audio.loudness.builtin-speaker-spl-range-size";

    private static final int SPL_RANGE_UNKNOWN = 0;
    private static final int SPL_RANGE_SMALL = 1;
    private static final int SPL_RANGE_MEDIUM = 2;
    private static final int SPL_RANGE_LARGE = 3;

    /** The possible transducer SPL ranges as defined in CTA2075 */
    @IntDef({
            SPL_RANGE_UNKNOWN,
            SPL_RANGE_SMALL,
            SPL_RANGE_MEDIUM,
            SPL_RANGE_LARGE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeviceSplRange {}

    private static final class LoudnessRemoteCallbackList extends
            RemoteCallbackList<ILoudnessCodecUpdatesDispatcher> {
        private final LoudnessCodecHelper mLoudnessCodecHelper;
        LoudnessRemoteCallbackList(LoudnessCodecHelper loudnessCodecHelper) {
            mLoudnessCodecHelper = loudnessCodecHelper;
        }

        @Override
        public void onCallbackDied(ILoudnessCodecUpdatesDispatcher callback, Object cookie) {
            Integer pid = null;
            if (cookie instanceof Integer) {
                pid = (Integer) cookie;
            }
            if (pid != null) {
                mLoudnessCodecHelper.removePid(pid);
            }
            super.onCallbackDied(callback, cookie);
        }
    }

    private final LoudnessRemoteCallbackList mLoudnessUpdateDispatchers =
            new LoudnessRemoteCallbackList(this);

    private final Object mLock = new Object();

    /** Contains for each started piid the set corresponding to unique registered audio codecs. */
    @GuardedBy("mLock")
    private final SparseArray<Set<LoudnessCodecInfo>> mStartedPiids = new SparseArray<>();

    /** Contains the current device id assignment for each piid. */
    @GuardedBy("mLock")
    private final SparseIntArray mPiidToDeviceIdCache = new SparseIntArray();

    /** Maps each piid to the owner process of the player. */
    @GuardedBy("mLock")
    private final SparseIntArray mPiidToPidCache = new SparseIntArray();

    private final AudioService mAudioService;

    /** Contains the properties necessary to compute the codec loudness related parameters. */
    private static final class LoudnessCodecInputProperties {
        private final int mMetadataType;

        private final boolean mIsDownmixing;

        @DeviceSplRange
        private final int mDeviceSplRange;

        static final class Builder {
            private int mMetadataType;

            private boolean mIsDownmixing;

            @DeviceSplRange
            private int mDeviceSplRange;

            Builder setMetadataType(int metadataType) {
                mMetadataType = metadataType;
                return this;
            }
            Builder setIsDownmixing(boolean isDownmixing) {
                mIsDownmixing = isDownmixing;
                return this;
            }
            Builder setDeviceSplRange(@DeviceSplRange int deviceSplRange) {
                mDeviceSplRange = deviceSplRange;
                return this;
            }

            LoudnessCodecInputProperties build() {
                return new LoudnessCodecInputProperties(mMetadataType,
                        mIsDownmixing, mDeviceSplRange);
            }
        }

        private LoudnessCodecInputProperties(int metadataType,
                                             boolean isDownmixing,
                                             @DeviceSplRange int deviceSplRange) {
            mMetadataType = metadataType;
            mIsDownmixing = isDownmixing;
            mDeviceSplRange = deviceSplRange;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            // type check and cast
            if (getClass() != obj.getClass()) {
                return false;
            }
            final LoudnessCodecInputProperties lcip = (LoudnessCodecInputProperties) obj;
            return mMetadataType == lcip.mMetadataType
                    && mIsDownmixing == lcip.mIsDownmixing
                    && mDeviceSplRange == lcip.mDeviceSplRange;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mMetadataType, mIsDownmixing, mDeviceSplRange);
        }

        @Override
        public String toString() {
            return "Loudness properties:"
                    + " device SPL range: " + splRangeToString(mDeviceSplRange)
                    + " down-mixing: " + mIsDownmixing
                    + " metadata type: " + mMetadataType;
        }

        PersistableBundle createLoudnessParameters() {
            // TODO: create bundle with new parameters
            return new PersistableBundle();
        }

    }

    @GuardedBy("mLock")
    private final HashMap<LoudnessCodecInputProperties, PersistableBundle> mCachedProperties =
            new HashMap<>();

    LoudnessCodecHelper(@NonNull AudioService audioService) {
        mAudioService = Objects.requireNonNull(audioService);
    }

    void registerLoudnessCodecUpdatesDispatcher(ILoudnessCodecUpdatesDispatcher dispatcher) {
        mLoudnessUpdateDispatchers.register(dispatcher, Binder.getCallingPid());
    }

    void unregisterLoudnessCodecUpdatesDispatcher(
            ILoudnessCodecUpdatesDispatcher dispatcher) {
        mLoudnessUpdateDispatchers.unregister(dispatcher);
    }

    void startLoudnessCodecUpdates(int piid, List<LoudnessCodecInfo> codecInfoList) {
        if (DEBUG) {
            Log.d(TAG, "startLoudnessCodecUpdates: piid " + piid + " codecInfos " + codecInfoList);
        }
        Set<LoudnessCodecInfo> infoSet;
        synchronized (mLock) {
            if (mStartedPiids.contains(piid)) {
                Log.w(TAG, "Already started loudness updates for piid " + piid);
                return;
            }
            infoSet = new HashSet<>(codecInfoList);
            mStartedPiids.put(piid, infoSet);

            mPiidToPidCache.put(piid, Binder.getCallingPid());
        }

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            mAudioService.getActivePlaybackConfigurations().stream().filter(
                    conf -> conf.getPlayerInterfaceId() == piid).findFirst().ifPresent(
                            apc -> updateCodecParametersForConfiguration(apc, infoSet));
        }
    }

    void stopLoudnessCodecUpdates(int piid) {
        if (DEBUG) {
            Log.d(TAG, "stopLoudnessCodecUpdates: piid " + piid);
        }
        synchronized (mLock) {
            if (!mStartedPiids.contains(piid)) {
                Log.w(TAG, "Loudness updates are already stopped for piid " + piid);
                return;
            }
            mStartedPiids.remove(piid);
            mPiidToDeviceIdCache.delete(piid);
            mPiidToPidCache.delete(piid);
        }
    }

    void addLoudnessCodecInfo(int piid, LoudnessCodecInfo info) {
        if (DEBUG) {
            Log.d(TAG, "addLoudnessCodecInfo: piid " + piid + " info " + info);
        }

        Set<LoudnessCodecInfo> infoSet;
        synchronized (mLock) {
            if (!mStartedPiids.contains(piid)) {
                Log.w(TAG, "Cannot add new loudness info for stopped piid " + piid);
                return;
            }

            infoSet = mStartedPiids.get(piid);
            infoSet.add(info);
        }

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            mAudioService.getActivePlaybackConfigurations().stream().filter(
                    conf -> conf.getPlayerInterfaceId() == piid).findFirst().ifPresent(
                            apc -> updateCodecParametersForConfiguration(apc, Set.of(info)));
        }
    }

    void removeLoudnessCodecInfo(int piid, LoudnessCodecInfo codecInfo) {
        if (DEBUG) {
            Log.d(TAG, "removeLoudnessCodecInfo: piid " + piid + " info " + codecInfo);
        }
        synchronized (mLock) {
            if (!mStartedPiids.contains(piid)) {
                Log.w(TAG, "Cannot remove loudness info for stopped piid " + piid);
                return;
            }
            final Set<LoudnessCodecInfo> infoSet = mStartedPiids.get(piid);
            infoSet.remove(codecInfo);
        }
    }

    void removePid(int pid) {
        if (DEBUG) {
            Log.d(TAG, "Removing pid " + pid + " from receiving updates");
        }
        synchronized (mLock) {
            for (int i = 0; i < mPiidToPidCache.size(); ++i) {
                int piid = mPiidToPidCache.keyAt(i);
                if (mPiidToPidCache.get(piid) == pid) {
                    if (DEBUG) {
                        Log.d(TAG, "Removing piid  " + piid);
                    }
                    mStartedPiids.delete(piid);
                    mPiidToDeviceIdCache.delete(piid);
                }
            }
        }
    }

    PersistableBundle getLoudnessParams(int piid, LoudnessCodecInfo codecInfo) {
        if (DEBUG) {
            Log.d(TAG, "getLoudnessParams: piid " + piid + " codecInfo " + codecInfo);
        }
        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            final List<AudioPlaybackConfiguration> configs =
                    mAudioService.getActivePlaybackConfigurations();

            for (final AudioPlaybackConfiguration apc : configs) {
                if (apc.getPlayerInterfaceId() == piid) {
                    final AudioDeviceInfo info = apc.getAudioDeviceInfo();
                    if (info == null) {
                        Log.i(TAG, "Player with piid " + piid + " is not assigned any device");
                        break;
                    }
                    synchronized (mLock) {
                        return getCodecBundle_l(info, codecInfo);
                    }
                }
            }
        }

        // return empty Bundle
        return new PersistableBundle();
    }

    /** Method to be called whenever there is a changed in the active playback configurations. */
    void updateCodecParameters(List<AudioPlaybackConfiguration> configs) {
        if (DEBUG) {
            Log.d(TAG, "updateCodecParameters: configs " + configs);
        }

        List<AudioPlaybackConfiguration> updateApcList = new ArrayList<>();
        synchronized (mLock) {
            for (final AudioPlaybackConfiguration apc : configs) {
                int piid = apc.getPlayerInterfaceId();
                int cachedDeviceId = mPiidToDeviceIdCache.get(piid, PLAYER_DEVICEID_INVALID);
                AudioDeviceInfo deviceInfo = apc.getAudioDeviceInfo();
                if (deviceInfo == null) {
                    if (DEBUG) {
                        Log.d(TAG, "No device info for piid: " + piid);
                    }
                    if (cachedDeviceId != PLAYER_DEVICEID_INVALID) {
                        mPiidToDeviceIdCache.delete(piid);
                        if (DEBUG) {
                            Log.d(TAG, "Remove cached device id for piid: " + piid);
                        }
                    }
                    continue;
                }
                if (cachedDeviceId == deviceInfo.getId()) {
                    // deviceId did not change
                    if (DEBUG) {
                        Log.d(TAG, "DeviceId " + cachedDeviceId + " for piid: " + piid
                                + " did not change");
                    }
                    continue;
                }
                mPiidToDeviceIdCache.put(piid, deviceInfo.getId());
                if (mStartedPiids.contains(piid)) {
                    updateApcList.add(apc);
                }
            }
        }

        updateApcList.forEach(apc -> updateCodecParametersForConfiguration(apc, null));
    }

    /** Updates and dispatches the new loudness parameters for the {@code codecInfos} set.
     *
     * @param apc the player configuration for which the loudness parameters are updated.
     * @param codecInfos the codec info for which the parameters are updated. If {@code null},
     *                   send updates for all the started codecs assigned to {@code apc}
     */
    private void updateCodecParametersForConfiguration(AudioPlaybackConfiguration apc,
            Set<LoudnessCodecInfo> codecInfos) {
        if (DEBUG) {
            Log.d(TAG, "updateCodecParametersForConfiguration apc:" + apc + " codecInfos: "
                    + codecInfos);
        }
        final PersistableBundle allBundles = new PersistableBundle();
        final int piid = apc.getPlayerInterfaceId();
        synchronized (mLock) {
            if (codecInfos == null) {
                codecInfos = mStartedPiids.get(piid);
            }

            final AudioDeviceInfo deviceInfo = apc.getAudioDeviceInfo();
            if (codecInfos != null && deviceInfo != null) {
                for (LoudnessCodecInfo info : codecInfos) {
                    allBundles.putPersistableBundle(Integer.toString(info.mediaCodecHashCode),
                            getCodecBundle_l(deviceInfo, info));
                }
            }
        }

        if (!allBundles.isDefinitelyEmpty()) {
            if (DEBUG) {
                Log.d(TAG, "Dispatching for piid: " + piid + " bundle: " + allBundles);
            }
            dispatchNewLoudnessParameters(piid, allBundles);
        }
    }

    private void dispatchNewLoudnessParameters(int piid, PersistableBundle bundle) {
        if (DEBUG) {
            Log.d(TAG, "dispatchNewLoudnessParameters: piid " + piid);
        }
        final int nbDispatchers = mLoudnessUpdateDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; ++i) {
            try {
                mLoudnessUpdateDispatchers.getBroadcastItem(i)
                        .dispatchLoudnessCodecParameterChange(piid, bundle);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching for piid: " + piid + " bundle: " + bundle , e);
            }
        }
        mLoudnessUpdateDispatchers.finishBroadcast();
    }

    @GuardedBy("mLock")
    private PersistableBundle getCodecBundle_l(AudioDeviceInfo deviceInfo,
                                             LoudnessCodecInfo codecInfo) {
        LoudnessCodecInputProperties.Builder builder = new LoudnessCodecInputProperties.Builder();
        LoudnessCodecInputProperties prop = builder.setDeviceSplRange(getDeviceSplRange(deviceInfo))
                .setIsDownmixing(codecInfo.isDownmixing)
                .setMetadataType(codecInfo.metadataType)
                .build();

        if (mCachedProperties.containsKey(prop)) {
            return mCachedProperties.get(prop);
        }
        final PersistableBundle codecBundle = prop.createLoudnessParameters();
        mCachedProperties.put(prop, codecBundle);
        return codecBundle;
    }

    @DeviceSplRange
    private int getDeviceSplRange(AudioDeviceInfo deviceInfo) {
        final int internalDeviceType = deviceInfo.getInternalType();
        if (internalDeviceType == AudioSystem.DEVICE_OUT_SPEAKER) {
            final String splRange = SystemProperties.get(
                    SYSTEM_PROPERTY_SPEAKER_SPL_RANGE_SIZE, "unknown");
            if (!splRange.equals("unknown")) {
                return stringToSplRange(splRange);
            }

            @DeviceSplRange int result = SPL_RANGE_SMALL;  // default for phone/tablet/watch
            if (mAudioService.isPlatformAutomotive() || mAudioService.isPlatformTelevision()) {
                result = SPL_RANGE_MEDIUM;
            }

            return result;
        } else if (internalDeviceType == AudioSystem.DEVICE_OUT_USB_HEADSET
                || internalDeviceType == AudioSystem.DEVICE_OUT_WIRED_HEADPHONE
                || internalDeviceType == AudioSystem.DEVICE_OUT_WIRED_HEADSET
                || (AudioSystem.isBluetoothDevice(internalDeviceType)
                && mAudioService.getBluetoothAudioDeviceCategory(deviceInfo.getAddress(),
                AudioSystem.isBluetoothLeDevice(internalDeviceType))
                == AUDIO_DEVICE_CATEGORY_HEADPHONES)) {
            return SPL_RANGE_LARGE;
        } else if (AudioSystem.isBluetoothDevice(internalDeviceType)) {
            final int audioDeviceType = mAudioService.getBluetoothAudioDeviceCategory(
                    deviceInfo.getAddress(), AudioSystem.isBluetoothLeDevice(internalDeviceType));
            if (audioDeviceType == AUDIO_DEVICE_CATEGORY_CARKIT) {
                return SPL_RANGE_MEDIUM;
            } else if (audioDeviceType == AUDIO_DEVICE_CATEGORY_WATCH) {
                return SPL_RANGE_SMALL;
            } else if (audioDeviceType == AUDIO_DEVICE_CATEGORY_HEARING_AID) {
                return SPL_RANGE_SMALL;
            }
        }

        return SPL_RANGE_UNKNOWN;
    }

    private static String splRangeToString(@DeviceSplRange int splRange) {
        switch (splRange) {
            case SPL_RANGE_LARGE: return "large";
            case SPL_RANGE_MEDIUM: return "medium";
            case SPL_RANGE_SMALL: return "small";
            default: return "unknown";
        }
    }

    @DeviceSplRange
    private static int stringToSplRange(String splRange) {
        switch (splRange) {
            case "large": return SPL_RANGE_LARGE;
            case "medium": return SPL_RANGE_MEDIUM;
            case "small": return SPL_RANGE_SMALL;
            default: return SPL_RANGE_UNKNOWN;
        }
    }
}
