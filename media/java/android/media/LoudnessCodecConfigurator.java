/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media;

import static android.media.AudioPlaybackConfiguration.PLAYER_PIID_INVALID;
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_4;
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_D;
import static android.media.audio.Flags.FLAG_LOUDNESS_CONFIGURATOR_API;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class for getting recommended loudness parameter updates for audio decoders, according to the
 * encoded format and current audio routing. Those updates can be automatically applied to the
 * {@link MediaCodec} instance(s), or be provided to the user. The codec loudness management
 * parameter updates are defined by the CTA-2075 standard.
 * <p>A new object should be instantiated for each {@link AudioTrack} with the help
 * of {@link #create()} or {@link #create(Executor, OnLoudnessCodecUpdateListener)}.
 */
@FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
public class LoudnessCodecConfigurator {
    private static final String TAG = "LoudnessCodecConfigurator";

    /**
     * Listener used for receiving asynchronous loudness metadata updates.
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public interface OnLoudnessCodecUpdateListener {
        /**
         * Contains the MediaCodec key/values that can be set directly to
         * configure the loudness of the handle's corresponding decoder (see
         * {@link MediaCodec#setParameters(Bundle)}).
         *
         * @param mediaCodec  the mediaCodec that will receive the new parameters
         * @param codecValues contains loudness key/value pairs that can be set
         *                    directly on the mediaCodec. The listener can modify
         *                    these values with their own edits which will be
         *                    returned for the mediaCodec configuration
         * @return a Bundle which contains the original computed codecValues
         * aggregated with user edits. The platform will configure the associated
         * MediaCodecs with the returned Bundle params.
         */
        @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
        @NonNull
        default Bundle onLoudnessCodecUpdate(@NonNull MediaCodec mediaCodec,
                                             @NonNull Bundle codecValues) {
            return codecValues;
        }
    }

    @NonNull private final LoudnessCodecDispatcher mLcDispatcher;

    private final Object mConfiguratorLock = new Object();

    @GuardedBy("mConfiguratorLock")
    private AudioTrack mAudioTrack;

    @GuardedBy("mConfiguratorLock")
    private final Executor mExecutor;

    @GuardedBy("mConfiguratorLock")
    private final OnLoudnessCodecUpdateListener mListener;

    @GuardedBy("mConfiguratorLock")
    private final HashMap<LoudnessCodecInfo, Set<MediaCodec>> mMediaCodecs = new HashMap<>();

    /**
     * Creates a new instance of {@link LoudnessCodecConfigurator}
     *
     * <p>This method should be used when the client does not need to alter the
     * codec loudness parameters before they are applied to the audio decoders.
     * Otherwise, use {@link #create(Executor, OnLoudnessCodecUpdateListener)}.
     *
     * @return the {@link LoudnessCodecConfigurator} instance
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public static @NonNull LoudnessCodecConfigurator create() {
        return new LoudnessCodecConfigurator(new LoudnessCodecDispatcher(AudioManager.getService()),
                Executors.newSingleThreadExecutor(), new OnLoudnessCodecUpdateListener() {});
    }

    /**
     * Creates a new instance of {@link LoudnessCodecConfigurator}
     *
     * <p>This method should be used when the client wants to alter the codec
     * loudness parameters before they are applied to the audio decoders.
     * Otherwise, use {@link #create()}.
     *
     * @param executor {@link Executor} to handle the callbacks
     * @param listener used for receiving updates
     *
     * @return the {@link LoudnessCodecConfigurator} instance
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public static @NonNull LoudnessCodecConfigurator create(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnLoudnessCodecUpdateListener listener) {
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(listener, "OnLoudnessCodecUpdateListener cannot be null");

        return new LoudnessCodecConfigurator(new LoudnessCodecDispatcher(AudioManager.getService()),
                executor, listener);
    }

    /**
     * Creates a new instance of {@link LoudnessCodecConfigurator}
     *
     * <p>This method should be used only in testing
     *
     * @param service interface for communicating with AudioService
     * @param executor {@link Executor} to handle the callbacks
     * @param listener used for receiving updates
     *
     * @return the {@link LoudnessCodecConfigurator} instance
     *
     * @hide
     */
    public static @NonNull LoudnessCodecConfigurator createForTesting(
            @NonNull IAudioService service,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnLoudnessCodecUpdateListener listener) {
        Objects.requireNonNull(service, "IAudioService cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(listener, "OnLoudnessCodecUpdateListener cannot be null");

        return new LoudnessCodecConfigurator(new LoudnessCodecDispatcher(service),
                executor, listener);
    }

    /** @hide */
    private LoudnessCodecConfigurator(@NonNull LoudnessCodecDispatcher lcDispatcher,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnLoudnessCodecUpdateListener listener) {
        mLcDispatcher = Objects.requireNonNull(lcDispatcher, "Dispatcher cannot be null");
        mExecutor = Objects.requireNonNull(executor, "Executor cannot be null");
        mListener = Objects.requireNonNull(listener,
                "OnLoudnessCodecUpdateListener cannot be null");
    }

    /**
     * Sets the {@link AudioTrack} and starts receiving asynchronous updates for
     * the registered {@link MediaCodec}s (see {@link #addMediaCodec(MediaCodec)})
     *
     * <p>The AudioTrack should be the one that receives audio data from the
     * added audio decoders and is used to determine the device routing on which
     * the audio streaming will take place. This will directly influence the
     * loudness parameters.
     * <p>After calling this method the framework will compute the initial set of
     * parameters which will be applied to the registered codecs/returned to the
     * listener for modification.
     *
     * @param audioTrack the track that will receive audio data from the provided
     *                   audio decoders. In case this is {@code null} this
     *                   method will have the effect of clearing the existing set
     *                   {@link AudioTrack} and will stop receiving asynchronous
     *                   loudness updates
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setAudioTrack(@Nullable AudioTrack audioTrack) {
        List<LoudnessCodecInfo> codecInfos;
        int piid = PLAYER_PIID_INVALID;
        int oldPiid = PLAYER_PIID_INVALID;
        synchronized (mConfiguratorLock) {
            if (mAudioTrack != null && mAudioTrack == audioTrack) {
                Log.v(TAG, "Loudness configurator already started for piid: "
                        + mAudioTrack.getPlayerIId());
                return;
            }

            codecInfos = getLoudnessCodecInfoList_l();
            if (mAudioTrack != null) {
                oldPiid = mAudioTrack.getPlayerIId();
                mLcDispatcher.removeLoudnessCodecListener(this);
            }
            if (audioTrack != null) {
                piid = audioTrack.getPlayerIId();
                mLcDispatcher.addLoudnessCodecListener(this, mExecutor, mListener);
            }

            mAudioTrack = audioTrack;
        }

        if (oldPiid != PLAYER_PIID_INVALID) {
            Log.v(TAG, "Loudness configurator stopping updates for piid: " + oldPiid);
            mLcDispatcher.stopLoudnessCodecUpdates(oldPiid);
        }
        if (piid != PLAYER_PIID_INVALID) {
            Log.v(TAG, "Loudness configurator starting updates for piid: " + piid);
            mLcDispatcher.startLoudnessCodecUpdates(piid, codecInfos);
        }
    }

    /**
     * Adds a new {@link MediaCodec} that will stream data to an {@link AudioTrack}
     * which the client sets
     * (see {@link LoudnessCodecConfigurator#setAudioTrack(AudioTrack)}).
     *
     * <p>This method can be called while asynchronous updates are live.
     *
     * <p>No new element will be added if the passed {@code mediaCodec} was
     * previously added.
     *
     * @param mediaCodec the codec to start receiving asynchronous loudness
     *                   updates. The codec has to be in a configured or started
     *                   state in order to add it for loudness updates.
     * @throws IllegalArgumentException if the same {@code mediaCodec} was already
     *                                  added before.
     * @return {@code false} if the {@code mediaCodec} was not configured or does
     *         not contain loudness metadata, {@code true} otherwise.
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public boolean addMediaCodec(@NonNull MediaCodec mediaCodec) {
        final MediaCodec mc = Objects.requireNonNull(mediaCodec,
                "MediaCodec for addMediaCodec cannot be null");
        int piid = PLAYER_PIID_INVALID;
        final LoudnessCodecInfo mcInfo = getCodecInfo(mc);

        if (mcInfo == null) {
            Log.v(TAG, "Could not extract codec loudness information");
            return false;
        }
        synchronized (mConfiguratorLock) {
            final AtomicBoolean containsCodec = new AtomicBoolean(false);
            Set<MediaCodec> newSet = mMediaCodecs.computeIfPresent(mcInfo, (info, codecSet) -> {
                containsCodec.set(!codecSet.add(mc));
                return codecSet;
            });
            if (newSet == null) {
                newSet = new HashSet<>();
                newSet.add(mc);
                mMediaCodecs.put(mcInfo, newSet);
            }
            if (containsCodec.get()) {
                throw new IllegalArgumentException(
                        "Loudness configurator already added " + mediaCodec);
            }
            if (mAudioTrack != null) {
                piid = mAudioTrack.getPlayerIId();
            }
        }

        if (piid != PLAYER_PIID_INVALID) {
            mLcDispatcher.addLoudnessCodecInfo(piid, mediaCodec.hashCode(), mcInfo);
        }

        return true;
    }

    /**
     * Removes the {@link MediaCodec} from receiving loudness updates.
     *
     * <p>This method can be called while asynchronous updates are live.
     *
     * <p>No elements will be removed if the passed mediaCodec was not added before.
     *
     * @param mediaCodec the element to remove for receiving asynchronous updates
     * @throws IllegalArgumentException if the {@code mediaCodec} was not configured,
     *                                  does not contain loudness metadata or if it
     *                                  was not added before
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeMediaCodec(@NonNull MediaCodec mediaCodec) {
        int piid = PLAYER_PIID_INVALID;
        LoudnessCodecInfo mcInfo;
        AtomicBoolean removedMc = new AtomicBoolean(false);
        AtomicBoolean removeInfo = new AtomicBoolean(false);

        mcInfo = getCodecInfo(Objects.requireNonNull(mediaCodec,
                "MediaCodec for removeMediaCodec cannot be null"));

        if (mcInfo == null) {
            throw new IllegalArgumentException("Could not extract codec loudness information");
        }
        synchronized (mConfiguratorLock) {
            if (mAudioTrack != null) {
                piid = mAudioTrack.getPlayerIId();
            }
            mMediaCodecs.computeIfPresent(mcInfo, (format, mcs) -> {
                removedMc.set(mcs.remove(mediaCodec));
                if (mcs.isEmpty()) {
                    // remove the entry
                    removeInfo.set(true);
                    return null;
                }
                return mcs;
            });
            if (!removedMc.get()) {
                throw new IllegalArgumentException(
                        "Loudness configurator does not contain " + mediaCodec);
            }
        }

        if (piid != PLAYER_PIID_INVALID && removeInfo.get()) {
            mLcDispatcher.removeLoudnessCodecInfo(piid, mcInfo);
        }
    }

    /**
     * Gets synchronous loudness updates when no listener is required. The provided
     * {@link MediaCodec} streams audio data to the passed {@link AudioTrack}.
     *
     * @param audioTrack track that receives audio data from the passed
     *                   {@link MediaCodec}
     * @param mediaCodec codec that decodes loudness annotated data for the passed
     *                   {@link AudioTrack}
     *
     * @return the {@link Bundle} containing the current loudness parameters. Caller is
     * responsible to update the {@link MediaCodec}
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    @NonNull
    public Bundle getLoudnessCodecParams(@NonNull AudioTrack audioTrack,
            @NonNull MediaCodec mediaCodec) {
        Objects.requireNonNull(audioTrack, "Passed audio track cannot be null");

        LoudnessCodecInfo codecInfo = getCodecInfo(mediaCodec);
        if (codecInfo == null) {
            return new Bundle();
        }

        return mLcDispatcher.getLoudnessCodecParams(audioTrack.getPlayerIId(), codecInfo);
    }

    /** @hide */
    /*package*/ int getAssignedTrackPiid() {
        int piid = PLAYER_PIID_INVALID;

        synchronized (mConfiguratorLock) {
            if (mAudioTrack == null) {
                return piid;
            }
            piid = mAudioTrack.getPlayerIId();
        }

        return piid;
    }

    /** @hide */
    /*package*/ Map<LoudnessCodecInfo, Set<MediaCodec>> getRegisteredMediaCodecs() {
        synchronized (mConfiguratorLock) {
            return mMediaCodecs;
        }
    }

    @GuardedBy("mConfiguratorLock")
    private List<LoudnessCodecInfo> getLoudnessCodecInfoList_l() {
        return mMediaCodecs.values().stream().flatMap(listMc -> listMc.stream().map(
                LoudnessCodecConfigurator::getCodecInfo)).toList();
    }

    @Nullable
    private static LoudnessCodecInfo getCodecInfo(@NonNull MediaCodec mediaCodec) {
        LoudnessCodecInfo lci = new LoudnessCodecInfo();
        final MediaCodecInfo codecInfo = mediaCodec.getCodecInfo();
        if (codecInfo.isEncoder()) {
            // loudness info only for decoders
            Log.w(TAG, "MediaCodec used for encoding does not support loudness annotation");
            return null;
        }

        try {
            final MediaFormat inputFormat = mediaCodec.getInputFormat();
            final String mimeType = inputFormat.getString(MediaFormat.KEY_MIME);
            if (MediaFormat.MIMETYPE_AUDIO_AAC.equalsIgnoreCase(mimeType)) {
                // check both KEY_AAC_PROFILE and KEY_PROFILE as some codecs may only recognize
                // one of these two keys
                int aacProfile = -1;
                int profile = -1;
                try {
                    aacProfile = inputFormat.getInteger(MediaFormat.KEY_AAC_PROFILE);
                } catch (NullPointerException e) {
                    // does not contain KEY_AAC_PROFILE. do nothing
                }
                try {
                    profile = inputFormat.getInteger(MediaFormat.KEY_PROFILE);
                } catch (NullPointerException e) {
                    // does not contain KEY_PROFILE. do nothing
                }
                if (aacProfile == MediaCodecInfo.CodecProfileLevel.AACObjectXHE
                        || profile == MediaCodecInfo.CodecProfileLevel.AACObjectXHE) {
                    lci.metadataType = CODEC_METADATA_TYPE_MPEG_D;
                } else {
                    lci.metadataType = CODEC_METADATA_TYPE_MPEG_4;
                }
            } else {
                Log.w(TAG, "MediaCodec mime type not supported for loudness annotation");
                return null;
            }

            final MediaFormat outputFormat = mediaCodec.getOutputFormat();
            lci.isDownmixing = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    < inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        } catch (IllegalStateException e) {
            Log.e(TAG, "MediaCodec is not configured", e);
            return null;
        }

        return lci;
    }
}
