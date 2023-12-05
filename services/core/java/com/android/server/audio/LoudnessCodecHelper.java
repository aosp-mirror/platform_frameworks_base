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
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_4;
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_D;
import static android.media.MediaFormat.KEY_AAC_DRC_EFFECT_TYPE;
import static android.media.MediaFormat.KEY_AAC_DRC_HEAVY_COMPRESSION;
import static android.media.MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL;
import static android.media.audio.Flags.automaticBtDeviceType;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.media.AudioDeviceInfo;
import android.media.AudioManager.AudioDeviceCategory;
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
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.audio.AudioServiceEvents.LoudnessEvent;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    @VisibleForTesting
    static final int SPL_RANGE_UNKNOWN = 0;
    @VisibleForTesting
    static final int SPL_RANGE_SMALL = 1;
    @VisibleForTesting
    static final int SPL_RANGE_MEDIUM = 2;
    @VisibleForTesting
    static final int SPL_RANGE_LARGE = 3;

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
                if (DEBUG) {
                    Log.d(TAG, "Client with pid " + pid + " died, removing from receiving updates");
                }
                sLogger.enqueue(LoudnessEvent.getClientDied(pid));
                mLoudnessCodecHelper.onClientPidDied(pid);
            }
            super.onCallbackDied(callback, cookie);
        }
    }

    private static final EventLogger sLogger = new EventLogger(
            AudioService.LOG_NB_EVENTS_LOUDNESS_CODEC, "Loudness updates");

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
    @VisibleForTesting
    static final class LoudnessCodecInputProperties {
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
            PersistableBundle loudnessParams = new PersistableBundle();

            switch (mDeviceSplRange) {
                case SPL_RANGE_LARGE:
                    // corresponds to -31dB attenuation
                    loudnessParams.putInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, 124);
                    if (mMetadataType == CODEC_METADATA_TYPE_MPEG_4) {
                        loudnessParams.putInt(KEY_AAC_DRC_HEAVY_COMPRESSION, 0);
                    } else if (mMetadataType == CODEC_METADATA_TYPE_MPEG_D) {
                        // general compression
                        loudnessParams.putInt(KEY_AAC_DRC_EFFECT_TYPE, 6);
                    }
                    break;
                case SPL_RANGE_MEDIUM:
                    // corresponds to -24dB attenuation
                    loudnessParams.putInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, 96);
                    if (mMetadataType == CODEC_METADATA_TYPE_MPEG_4) {
                        loudnessParams.putInt(KEY_AAC_DRC_HEAVY_COMPRESSION, mIsDownmixing ? 1 : 0);
                    } else if (mMetadataType == CODEC_METADATA_TYPE_MPEG_D) {
                        // general compression
                        loudnessParams.putInt(KEY_AAC_DRC_EFFECT_TYPE, 6);
                    }
                    break;
                case SPL_RANGE_SMALL:
                    // corresponds to -16dB attenuation
                    loudnessParams.putInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, 64);
                    if (mMetadataType == CODEC_METADATA_TYPE_MPEG_4) {
                        loudnessParams.putInt(KEY_AAC_DRC_HEAVY_COMPRESSION, 1);
                    } else if (mMetadataType == CODEC_METADATA_TYPE_MPEG_D) {
                        // limited playback range compression
                        loudnessParams.putInt(KEY_AAC_DRC_EFFECT_TYPE, 3);
                    }
                    break;
                default:
                    // corresponds to -24dB attenuation
                    loudnessParams.putInt(KEY_AAC_DRC_TARGET_REFERENCE_LEVEL, 96);
                    if (mMetadataType == CODEC_METADATA_TYPE_MPEG_4) {
                        loudnessParams.putInt(KEY_AAC_DRC_HEAVY_COMPRESSION, mIsDownmixing ? 1 : 0);
                    } else if (mMetadataType == CODEC_METADATA_TYPE_MPEG_D) {
                        // general compression
                        loudnessParams.putInt(KEY_AAC_DRC_EFFECT_TYPE, 6);
                    }
                    break;
            }

            return loudnessParams;
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

        synchronized (mLock) {
            if (mStartedPiids.contains(piid)) {
                Log.w(TAG, "Already started loudness updates for piid " + piid);
                return;
            }
            Set<LoudnessCodecInfo> infoSet = new HashSet<>(codecInfoList);
            mStartedPiids.put(piid, infoSet);

            int pid = Binder.getCallingPid();
            mPiidToPidCache.put(piid, pid);

            sLogger.enqueue(LoudnessEvent.getStartPiid(piid, pid));
        }

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            mAudioService.getActivePlaybackConfigurations().stream().filter(
                    conf -> conf.getPlayerInterfaceId() == piid).findFirst().ifPresent(
                    this::updateCodecParametersForConfiguration);
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

            sLogger.enqueue(LoudnessEvent.getStopPiid(piid, mPiidToPidCache.get(piid, -1)));
            mPiidToDeviceIdCache.delete(piid);
            mPiidToPidCache.delete(piid);
        }
    }

    void addLoudnessCodecInfo(int piid, int mediaCodecHash, LoudnessCodecInfo info) {
        if (DEBUG) {
            Log.d(TAG, "addLoudnessCodecInfo: piid " + piid + " mcHash " + mediaCodecHash + " info "
                    + info);
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
                            apc -> {
                                final AudioDeviceInfo deviceInfo = apc.getAudioDeviceInfo();
                                if (deviceInfo != null) {
                                    PersistableBundle updateBundle = new PersistableBundle();
                                    synchronized (mLock) {
                                        updateBundle.putPersistableBundle(
                                                Integer.toString(mediaCodecHash),
                                                getCodecBundle_l(deviceInfo, info));
                                    }
                                    if (!updateBundle.isDefinitelyEmpty()) {
                                        dispatchNewLoudnessParameters(piid, updateBundle);
                                    }
                                }
                            });
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

        updateApcList.forEach(apc -> updateCodecParametersForConfiguration(apc));
    }

    /** Updates and dispatches the new loudness parameters for all its registered codecs. */
    void dump(PrintWriter pw) {
        // Registered clients
        pw.println("\nRegistered clients:\n");
        synchronized (mLock) {
            for (int i = 0; i < mStartedPiids.size(); ++i) {
                int piid = mStartedPiids.keyAt(i);
                int pid = mPiidToPidCache.get(piid, -1);
                final Set<LoudnessCodecInfo> codecInfos = mStartedPiids.get(piid);
                pw.println(String.format("Player piid %d pid %d active codec types %s\n", piid,
                        pid, codecInfos.stream().map(Object::toString).collect(
                                Collectors.joining(", "))));
            }
            pw.println();
        }

        sLogger.dump(pw);
        pw.println();
    }

    private void onClientPidDied(int pid) {
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

    /**
     * Updates and dispatches the new loudness parameters for the {@code codecInfos} set.
     *
     * @param apc the player configuration for which the loudness parameters are updated.
     */
    private void updateCodecParametersForConfiguration(AudioPlaybackConfiguration apc) {
        if (DEBUG) {
            Log.d(TAG, "updateCodecParametersForConfiguration apc:" + apc);
        }

        final PersistableBundle allBundles = new PersistableBundle();
        final int piid = apc.getPlayerInterfaceId();

        synchronized (mLock) {
            final Set<LoudnessCodecInfo> codecInfos = mStartedPiids.get(piid);

            final AudioDeviceInfo deviceInfo = apc.getAudioDeviceInfo();
            if (codecInfos != null && deviceInfo != null) {
                for (LoudnessCodecInfo info : codecInfos) {
                    allBundles.putPersistableBundle(Integer.toString(info.hashCode()),
                            getCodecBundle_l(deviceInfo, info));
                }
            }
        }

        if (!allBundles.isDefinitelyEmpty()) {
            dispatchNewLoudnessParameters(piid, allBundles);
        }
    }

    private void dispatchNewLoudnessParameters(int piid, PersistableBundle bundle) {
        if (DEBUG) {
            Log.d(TAG, "dispatchNewLoudnessParameters: piid " + piid + " bundle: " + bundle);
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
        final @AudioDeviceCategory int deviceCategory;
        if (automaticBtDeviceType()) {
            deviceCategory = mAudioService.getBluetoothAudioDeviceCategory(deviceInfo.getAddress());
        } else {
            deviceCategory = mAudioService.getBluetoothAudioDeviceCategory_legacy(
                    deviceInfo.getAddress(), AudioSystem.isBluetoothLeDevice(internalDeviceType));
        }
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
                && deviceCategory == AUDIO_DEVICE_CATEGORY_HEADPHONES)) {
            return SPL_RANGE_LARGE;
        } else if (AudioSystem.isBluetoothDevice(internalDeviceType)) {
            if (deviceCategory == AUDIO_DEVICE_CATEGORY_CARKIT) {
                return SPL_RANGE_MEDIUM;
            } else if (deviceCategory == AUDIO_DEVICE_CATEGORY_WATCH) {
                return SPL_RANGE_SMALL;
            } else if (deviceCategory == AUDIO_DEVICE_CATEGORY_HEARING_AID) {
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
