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
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
     * - "small": for max SPL with test signal < 75 dB,
     * - "medium": for max SPL with test signal between 70 and 90 dB,
     * - "large": for max SPL with test signal > 85 dB.
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
    public @interface DeviceSplRange {
    }

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

    /** Contains for each started track id the known started piids. */
    @GuardedBy("mLock")
    private final HashMap<LoudnessTrackId, Set<Integer>> mStartedConfigPiids =
            new HashMap<>();

    /** Contains for each LoudnessTrackId a set of started coudec infos. */
    @GuardedBy("mLock")
    private final HashMap<LoudnessTrackId, Set<LoudnessCodecInfo>> mStartedConfigInfo =
            new HashMap<>();

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

    /**
     * Contains the properties necessary to identify the tracks that are receiving annotated
     * loudness data.
     **/
    @VisibleForTesting
    static final class LoudnessTrackId {
        private final int mSessionId;

        private final int mPid;

        private LoudnessTrackId(int sessionId, int pid) {
            mSessionId = sessionId;
            mPid = pid;
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
            final LoudnessTrackId lti = (LoudnessTrackId) obj;
            return mSessionId == lti.mSessionId && mPid == lti.mPid;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mSessionId, mPid);
        }

        @Override
        public String toString() {
            return "Loudness track id:"
                    + " session ID: " + mSessionId
                    + " pid: " + mPid;
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

    void startLoudnessCodecUpdates(int sessionId) {
        int pid = Binder.getCallingPid();
        if (DEBUG) {
            Log.d(TAG,
                    "startLoudnessCodecUpdates: sessionId " + sessionId + " pid " + pid);
        }

        final LoudnessTrackId newConfig = new LoudnessTrackId(sessionId, pid);
        HashSet<Integer> newPiids;
        synchronized (mLock) {
            if (mStartedConfigInfo.containsKey(newConfig)) {
                Log.w(TAG, "Already started loudness updates for config: " + newConfig);
                return;
            }

            mStartedConfigInfo.put(newConfig, new HashSet<>());
            newPiids = new HashSet<>();
            mStartedConfigPiids.put(newConfig, newPiids);
        }

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            mAudioService.getActivePlaybackConfigurations().stream().filter(
                    conf -> conf.getSessionId() == sessionId
                            && conf.getClientPid() == pid).forEach(apc -> {
                                int piid = apc.getPlayerInterfaceId();
                                synchronized (mLock) {
                                    newPiids.add(piid);
                                    mPiidToPidCache.put(piid, pid);
                                    sLogger.enqueue(LoudnessEvent.getStartPiid(piid, pid));
                                }
                            });
        }
    }

    void stopLoudnessCodecUpdates(int sessionId) {
        int pid = Binder.getCallingPid();
        if (DEBUG) {
            Log.d(TAG,
                    "stopLoudnessCodecUpdates: sessionId " + sessionId + " pid " + pid);
        }

        final LoudnessTrackId config = new LoudnessTrackId(sessionId, pid);
        synchronized (mLock) {
            if (!mStartedConfigInfo.containsKey(config)) {
                Log.w(TAG, "Loudness updates are already stopped config: " + config);
                return;
            }

            final Set<Integer> startedPiidSet = mStartedConfigPiids.get(config);
            if (startedPiidSet == null) {
                Log.e(TAG, "Loudness updates are already stopped config: " + config);
                return;
            }
            for (Integer piid : startedPiidSet) {
                sLogger.enqueue(LoudnessEvent.getStopPiid(piid, mPiidToPidCache.get(piid, -1)));
                mPiidToDeviceIdCache.delete(piid);
                mPiidToPidCache.delete(piid);
            }
            mStartedConfigPiids.remove(config);
            mStartedConfigInfo.remove(config);
        }
    }

    void addLoudnessCodecInfo(int sessionId, int mediaCodecHash,
            LoudnessCodecInfo info) {
        int pid = Binder.getCallingPid();
        if (DEBUG) {
            Log.d(TAG, "addLoudnessCodecInfo: sessionId " + sessionId
                    + " mcHash " + mediaCodecHash + " info " + info + " pid " + pid);
        }

        final LoudnessTrackId config = new LoudnessTrackId(sessionId, pid);
        Set<LoudnessCodecInfo> infoSet;
        Set<Integer> piids;
        synchronized (mLock) {
            if (!mStartedConfigInfo.containsKey(config) || !mStartedConfigPiids.containsKey(
                    config)) {
                Log.w(TAG, "Cannot add new loudness info for stopped config " + config);
                return;
            }

            piids = mStartedConfigPiids.get(config);
            infoSet = mStartedConfigInfo.get(config);
            infoSet.add(info);
        }

        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            final PersistableBundle updateBundle = new PersistableBundle();
            Optional<AudioPlaybackConfiguration> apc =
                    mAudioService.getActivePlaybackConfigurations().stream().filter(
                            conf -> conf.getSessionId() == sessionId
                                    && conf.getClientPid() == pid).findFirst();
            if (apc.isEmpty()) {
                if (DEBUG) {
                    Log.d(TAG,
                            "No APCs found when adding loudness codec info. Using AudioAttributes"
                                    + " routing for initial update");
                }
                updateBundle.putPersistableBundle(Integer.toString(mediaCodecHash),
                        getLoudnessParams(info));
            } else {
                final AudioDeviceInfo deviceInfo = apc.get().getAudioDeviceInfo();
                if (deviceInfo != null) {
                    synchronized (mLock) {
                        // found a piid that matches the configuration
                        piids.add(apc.get().getPlayerInterfaceId());

                        updateBundle.putPersistableBundle(
                                Integer.toString(mediaCodecHash),
                                getCodecBundle_l(deviceInfo.getInternalType(),
                                        deviceInfo.getAddress(), info));
                    }
                }
            }
            if (!updateBundle.isDefinitelyEmpty()) {
                dispatchNewLoudnessParameters(sessionId, updateBundle);
            }
        }
    }

    void removeLoudnessCodecInfo(int sessionId, LoudnessCodecInfo codecInfo) {
        if (DEBUG) {
            Log.d(TAG, "removeLoudnessCodecInfo: session ID" + sessionId + " info " + codecInfo);
        }

        int pid = Binder.getCallingPid();
        final LoudnessTrackId config = new LoudnessTrackId(sessionId, pid);
        synchronized (mLock) {
            if (!mStartedConfigInfo.containsKey(config) || !mStartedConfigPiids.containsKey(
                    config)) {
                Log.w(TAG, "Cannot remove loudness info for stopped config " + config);
                return;
            }
            final Set<LoudnessCodecInfo> codecInfos = mStartedConfigInfo.get(config);
            if (!codecInfos.remove(codecInfo)) {
                Log.w(TAG, "Could not find to remove codecInfo " + codecInfo);
            }
        }
    }

    PersistableBundle getLoudnessParams(LoudnessCodecInfo codecInfo) {
        if (DEBUG) {
            Log.d(TAG, "getLoudnessParams: codecInfo " + codecInfo);
        }
        final ArrayList<AudioDeviceAttributes> devicesForAttributes =
                mAudioService.getDevicesForAttributesInt(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(), /*forVolume=*/false);
        if (!devicesForAttributes.isEmpty()) {
            final AudioDeviceAttributes audioDeviceAttribute = devicesForAttributes.get(0);
            synchronized (mLock) {
                return getCodecBundle_l(audioDeviceAttribute.getInternalType(),
                        audioDeviceAttribute.getAddress(), codecInfo);
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
                final LoudnessTrackId config = new LoudnessTrackId(apc.getSessionId(),
                        apc.getClientPid());
                if (mStartedConfigInfo.containsKey(config) && mStartedConfigPiids.containsKey(
                        config)) {
                    if (DEBUG) {
                        Log.d(TAG, "Updating config: " + config + " with APC " + apc);
                    }
                    updateApcList.add(apc);
                    // update the started piid set
                    mStartedConfigPiids.get(config).add(piid);
                }
            }
        }

        updateApcList.forEach(this::updateCodecParametersForConfiguration);
    }

    /** Updates and dispatches the new loudness parameters for all its registered codecs. */
    void dump(PrintWriter pw) {
        // Registered clients
        pw.println("\nRegistered clients:\n");
        synchronized (mLock) {
            for (Map.Entry<LoudnessTrackId, Set<Integer>> entry : mStartedConfigPiids.entrySet()) {
                for (Integer piid : entry.getValue()) {
                    int pid = mPiidToPidCache.get(piid, -1);
                    final Set<LoudnessCodecInfo> codecInfos = mStartedConfigInfo.get(
                            entry.getKey());
                    if (codecInfos != null) {
                        pw.println(
                                String.format("Player piid %d pid %d active codec types %s\n", piid,
                                        pid, codecInfos.stream().map(Object::toString).collect(
                                                Collectors.joining(", "))));
                    }
                }
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
                    mPiidToDeviceIdCache.delete(piid);
                }
            }

            mStartedConfigPiids.entrySet().removeIf(entry -> entry.getKey().mPid == pid);
            mStartedConfigInfo.entrySet().removeIf(entry -> entry.getKey().mPid == pid);
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

        synchronized (mLock) {
            final LoudnessTrackId config = new LoudnessTrackId(apc.getSessionId(),
                    apc.getClientPid());
            final Set<LoudnessCodecInfo> codecInfos = mStartedConfigInfo.get(config);
            final AudioDeviceInfo deviceInfo = apc.getAudioDeviceInfo();

            if (codecInfos != null && deviceInfo != null) {
                for (LoudnessCodecInfo info : codecInfos) {
                    if (info != null) {
                        allBundles.putPersistableBundle(Integer.toString(info.hashCode()),
                                getCodecBundle_l(deviceInfo.getInternalType(),
                                        deviceInfo.getAddress(), info));
                    }
                }
            }
        }

        if (!allBundles.isDefinitelyEmpty()) {
            dispatchNewLoudnessParameters(apc.getSessionId(), allBundles);
        }
    }

    private void dispatchNewLoudnessParameters(int sessionId,
            PersistableBundle bundle) {
        if (DEBUG) {
            Log.d(TAG,
                    "dispatchNewLoudnessParameters: sessionId " + sessionId + " bundle: " + bundle);
        }
        final int nbDispatchers = mLoudnessUpdateDispatchers.beginBroadcast();
        for (int i = 0; i < nbDispatchers; ++i) {
            try {
                mLoudnessUpdateDispatchers.getBroadcastItem(i)
                        .dispatchLoudnessCodecParameterChange(sessionId, bundle);
            } catch (RemoteException e) {
                Log.e(TAG, "Error dispatching for sessionId " + sessionId + " bundle: " + bundle,
                        e);
            }
        }
        mLoudnessUpdateDispatchers.finishBroadcast();
    }

    @GuardedBy("mLock")
    private PersistableBundle getCodecBundle_l(int internalDeviceType,
            String address,
            LoudnessCodecInfo codecInfo) {
        LoudnessCodecInputProperties.Builder builder = new LoudnessCodecInputProperties.Builder();
        LoudnessCodecInputProperties prop = builder.setDeviceSplRange(
                        getDeviceSplRange(internalDeviceType, address))
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
    private int getDeviceSplRange(int internalDeviceType, String address) {
        @AudioDeviceCategory int deviceCategory;
        try (SafeCloseable ignored = ClearCallingIdentityContext.create()) {
            if (automaticBtDeviceType()) {
                deviceCategory = mAudioService.getBluetoothAudioDeviceCategory(address);
            } else {
                deviceCategory = mAudioService.getBluetoothAudioDeviceCategory_legacy(
                        address, AudioSystem.isBluetoothLeDevice(internalDeviceType));
            }
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
            case SPL_RANGE_LARGE:
                return "large";
            case SPL_RANGE_MEDIUM:
                return "medium";
            case SPL_RANGE_SMALL:
                return "small";
            default:
                return "unknown";
        }
    }

    @DeviceSplRange
    private static int stringToSplRange(String splRange) {
        switch (splRange) {
            case "large":
                return SPL_RANGE_LARGE;
            case "medium":
                return SPL_RANGE_MEDIUM;
            case "small":
                return SPL_RANGE_SMALL;
            default:
                return SPL_RANGE_UNKNOWN;
        }
    }
}
