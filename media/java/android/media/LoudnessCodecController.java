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

import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_4;
import static android.media.LoudnessCodecInfo.CodecMetadataType.CODEC_METADATA_TYPE_MPEG_D;
import static android.media.audio.Flags.FLAG_LOUDNESS_CONFIGURATOR_API;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.media.permission.SafeCloseable;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Class for getting recommended loudness parameter updates for audio decoders as they are used
 * to play back media content according to the encoded format and current audio routing. These
 * audio decoder updates leverage loudness metadata present in compressed audio streams. They
 * ensure the loudness and dynamic range of the content is optimized to the physical
 * characteristics of the audio output device (e.g. phone microspeakers vs headphones vs TV
 * speakers).Those updates can be automatically applied to the {@link MediaCodec} instance(s), or
 * be provided to the user. The codec loudness management parameter updates are computed in
 * accordance to the CTA-2075 standard.
 * <p>A new object should be instantiated for each audio session
 * (see {@link AudioManager#generateAudioSessionId()}) using creator methods {@link #create(int)} or
 * {@link #create(int, Executor, OnLoudnessCodecUpdateListener)}.
 */
@FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
public class LoudnessCodecController implements SafeCloseable {
    private static final String TAG = "LoudnessCodecController";

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
         *
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

    @NonNull
    private final LoudnessCodecDispatcher mLcDispatcher;

    private final Object mControllerLock = new Object();

    private final int mSessionId;

    @GuardedBy("mControllerLock")
    private final HashMap<LoudnessCodecInfo, Set<MediaCodec>> mMediaCodecs = new HashMap<>();

    /**
     * Creates a new instance of {@link LoudnessCodecController}
     *
     * <p>This method should be used when the client does not need to alter the
     * codec loudness parameters before they are applied to the audio decoders.
     * Otherwise, use {@link #create(int, Executor, OnLoudnessCodecUpdateListener)}.
     *
     * @param sessionId  the session ID of the track that will receive data
     *                        from the added {@link MediaCodec}'s
     *
     * @return the {@link LoudnessCodecController} instance
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public static @NonNull LoudnessCodecController create(int sessionId) {
        final LoudnessCodecDispatcher dispatcher = new LoudnessCodecDispatcher(
                AudioManager.getService());
        final LoudnessCodecController controller = new LoudnessCodecController(dispatcher,
                sessionId);
        dispatcher.addLoudnessCodecListener(controller, Executors.newSingleThreadExecutor(),
                new OnLoudnessCodecUpdateListener() {});
        dispatcher.startLoudnessCodecUpdates(sessionId);
        return controller;
    }

    /**
     * Creates a new instance of {@link LoudnessCodecController}
     *
     * <p>This method should be used when the client wants to alter the codec
     * loudness parameters before they are applied to the audio decoders.
     * Otherwise, use {@link #create( int)}.
     *
     * @param sessionId       the session ID of the track that will receive data
     *                        from the added {@link MediaCodec}'s
     * @param executor        {@link Executor} to handle the callbacks
     * @param listener        used for receiving updates
     *
     * @return the {@link LoudnessCodecController} instance
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public static @NonNull LoudnessCodecController create(
            int sessionId,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnLoudnessCodecUpdateListener listener) {
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(listener, "OnLoudnessCodecUpdateListener cannot be null");

        final LoudnessCodecDispatcher dispatcher = new LoudnessCodecDispatcher(
                AudioManager.getService());
        final LoudnessCodecController controller = new LoudnessCodecController(dispatcher,
                sessionId);
        dispatcher.addLoudnessCodecListener(controller, executor, listener);
        dispatcher.startLoudnessCodecUpdates(sessionId);
        return controller;
    }

    /**
     * Creates a new instance of {@link LoudnessCodecController}
     *
     * <p>This method should be used only in testing
     *
     * @param sessionId  the session ID of the track that will receive data
     *                        from the added {@link MediaCodec}'s
     * @param executor {@link Executor} to handle the callbacks
     * @param listener used for receiving updates
     * @param service  interface for communicating with AudioService
     *
     * @return the {@link LoudnessCodecController} instance
     * @hide
     */
    public static @NonNull LoudnessCodecController createForTesting(
            int sessionId,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnLoudnessCodecUpdateListener listener,
            @NonNull IAudioService service) {
        Objects.requireNonNull(service, "IAudioService cannot be null");
        Objects.requireNonNull(executor, "Executor cannot be null");
        Objects.requireNonNull(listener, "OnLoudnessCodecUpdateListener cannot be null");

        final LoudnessCodecDispatcher dispatcher = new LoudnessCodecDispatcher(service);
        final LoudnessCodecController controller = new LoudnessCodecController(dispatcher,
                sessionId);
        dispatcher.addLoudnessCodecListener(controller, executor, listener);
        dispatcher.startLoudnessCodecUpdates(sessionId);
        return controller;
    }

    /** @hide */
    private LoudnessCodecController(@NonNull LoudnessCodecDispatcher lcDispatcher, int sessionId) {
        mLcDispatcher = Objects.requireNonNull(lcDispatcher, "Dispatcher cannot be null");
        mSessionId = sessionId;
    }

    /**
     * Adds a new {@link MediaCodec} that will stream data to a player
     * which uses {@link #mSessionId}.
     *
     * <p>No new element will be added if the passed {@code mediaCodec} was
     * previously added.
     *
     * @param mediaCodec the codec to start receiving asynchronous loudness
     *                   updates. The codec has to be in a configured or started
     *                   state in order to add it for loudness updates.
     * @return {@code false} if the {@code mediaCodec} was not configured or does
     * not contain loudness metadata, {@code true} otherwise.
     * @throws IllegalArgumentException if the same {@code mediaCodec} was already
     *                                  added before.
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public boolean addMediaCodec(@NonNull MediaCodec mediaCodec) {
        final MediaCodec mc = Objects.requireNonNull(mediaCodec,
                "MediaCodec for addMediaCodec cannot be null");
        final LoudnessCodecInfo mcInfo = getCodecInfo(mc);

        if (mcInfo == null) {
            Log.v(TAG, "Could not extract codec loudness information");
            return false;
        }
        synchronized (mControllerLock) {
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
                        "Loudness controller already added " + mediaCodec);
            }
        }

        mLcDispatcher.addLoudnessCodecInfo(mSessionId, mediaCodec.hashCode(),
                mcInfo);

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
        LoudnessCodecInfo mcInfo;
        AtomicBoolean removedMc = new AtomicBoolean(false);
        AtomicBoolean removeInfo = new AtomicBoolean(false);

        mcInfo = getCodecInfo(Objects.requireNonNull(mediaCodec,
                "MediaCodec for removeMediaCodec cannot be null"));

        if (mcInfo == null) {
            throw new IllegalArgumentException("Could not extract codec loudness information");
        }
        synchronized (mControllerLock) {
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
                        "Loudness controller does not contain " + mediaCodec);
            }
        }

        if (removeInfo.get()) {
            mLcDispatcher.removeLoudnessCodecInfo(mSessionId, mcInfo);
        }
    }

    /**
     * Returns the loudness parameters of the registered audio decoders
     *
     * <p>Those parameters may have been automatically applied if the
     * {@code LoudnessCodecController} was created with {@link #create(int)}, or they are the
     * parameters that have been sent to the {@link OnLoudnessCodecUpdateListener} if using a
     * codec update listener.
     *
     * @param mediaCodec codec that decodes loudness annotated data. Has to be added
     *                   with {@link #addMediaCodec(MediaCodec)} before calling this
     *                   method
     * @throws IllegalArgumentException if the passed {@link MediaCodec} was not
     *                                  added before calling this method
     *
     * @return the {@link Bundle} containing the current loudness parameters.
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    @NonNull
    public Bundle getLoudnessCodecParams(@NonNull MediaCodec mediaCodec) {
        Objects.requireNonNull(mediaCodec, "MediaCodec cannot be null");

        LoudnessCodecInfo codecInfo = getCodecInfo(mediaCodec);
        if (codecInfo == null) {
            throw new IllegalArgumentException("MediaCodec does not have valid codec information");
        }

        synchronized (mControllerLock) {
            final Set<MediaCodec> codecs = mMediaCodecs.get(codecInfo);
            if (codecs == null || !codecs.contains(mediaCodec)) {
                throw new IllegalArgumentException(
                        "MediaCodec was not added for loudness annotation");
            }
        }

        return mLcDispatcher.getLoudnessCodecParams(codecInfo);
    }

    /**
     * Stops any loudness updates and frees up the resources.
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void release() {
        close();
    }

    /** @hide */
    @Override
    public void close() {
        synchronized (mControllerLock) {
            mMediaCodecs.clear();
        }
        mLcDispatcher.stopLoudnessCodecUpdates(mSessionId);
    }

    /** @hide */
    /*package*/ int getSessionId() {
        return mSessionId;
    }

    /** @hide */
    /*package*/ Map<LoudnessCodecInfo, Set<MediaCodec>> getRegisteredMediaCodecs() {
        synchronized (mControllerLock) {
            return mMediaCodecs;
        }
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
