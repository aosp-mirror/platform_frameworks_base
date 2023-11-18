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

import static android.media.audio.Flags.FLAG_LOUDNESS_CONFIGURATOR_API;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Class for getting recommended loudness parameter updates for audio decoders, according to the
 * encoded format and current audio routing. Those updates can be automatically applied to the
 * {@link MediaCodec} instance(s), or be provided to the user. The codec loudness management
 * updates are defined by the CTA-2075 standard.
 * <p>A new object should be instantiated for each {@link AudioTrack} with the help
 * of {@link AudioManager#createLoudnessCodecConfigurator()}.
 *
 * TODO: remove hide once API is final
 * @hide
 */
@FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
public class LoudnessCodecConfigurator {
    private static final String TAG = "LoudnessCodecConfigurator";

    /**
     * Listener used for receiving asynchronous loudness metadata updates.
     *
     * TODO: remove hide once API is final
     * @hide
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
         *
         * TODO: remove hide once API is final
         * @hide
         */
        @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
        @NonNull
        default Bundle onLoudnessCodecUpdate(@NonNull MediaCodec mediaCodec,
                                             @NonNull Bundle codecValues) {
            return codecValues;
        }
    }

    @NonNull private final LoudnessCodecDispatcher mLcDispatcher;

    private AudioTrack mAudioTrack;

    private final List<MediaCodec> mMediaCodecs = new ArrayList<>();

    /** @hide */
    protected LoudnessCodecConfigurator(@NonNull LoudnessCodecDispatcher lcDispatcher) {
        mLcDispatcher = Objects.requireNonNull(lcDispatcher);
    }


    /**
     * Starts receiving asynchronous loudness updates and registers the listener for
     * receiving {@link MediaCodec} loudness parameter updates.
     * <p>This method should be called before {@link #startLoudnessCodecUpdates()} or
     * after {@link #stopLoudnessCodecUpdates()}.
     *
     * @param executor {@link Executor} to handle the callbacks
     * @param listener used to receive updates
     *
     * @return {@code true} if there is at least one {@link MediaCodec} and
     * {@link AudioTrack} set and the user can expect receiving updates.
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public boolean startLoudnessCodecUpdates(@NonNull @CallbackExecutor Executor executor,
                                             @NonNull OnLoudnessCodecUpdateListener listener) {
        Objects.requireNonNull(executor,
                "Executor must not be null");
        Objects.requireNonNull(listener,
                "OnLoudnessCodecUpdateListener must not be null");
        mLcDispatcher.addLoudnessCodecListener(this, executor, listener);

        return checkStartLoudnessConfigurator();
    }

    /**
     * Starts receiving asynchronous loudness updates.
     * <p>The registered MediaCodecs will be updated automatically without any client
     * callbacks.
     *
     * @return {@code true} if there is at least one MediaCodec and AudioTrack set
     * (see {@link #setAudioTrack(AudioTrack)}, {@link #addMediaCodec(MediaCodec)})
     * and the user can expect receiving updates.
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public boolean startLoudnessCodecUpdates() {
        mLcDispatcher.addLoudnessCodecListener(this,
                Executors.newSingleThreadExecutor(), new OnLoudnessCodecUpdateListener() {});
        return checkStartLoudnessConfigurator();
    }

    /**
     * Stops receiving asynchronous loudness updates.
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void stopLoudnessCodecUpdates() {
        mLcDispatcher.removeLoudnessCodecListener(this);
    }

    /**
     * Adds a new {@link MediaCodec} that will stream data to an {@link AudioTrack}
     * which is registered through {@link #setAudioTrack(AudioTrack)}.
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void addMediaCodec(@NonNull MediaCodec mediaCodec) {
        mMediaCodecs.add(Objects.requireNonNull(mediaCodec,
                "MediaCodec for addMediaCodec must not be null"));
    }

    /**
     * Removes the {@link MediaCodec} from receiving loudness updates.
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void removeMediaCodec(@NonNull MediaCodec mediaCodec) {
        mMediaCodecs.remove(Objects.requireNonNull(mediaCodec,
                "MediaCodec for removeMediaCodec must not be null"));
    }

    /**
     * Sets the {@link AudioTrack} that can receive audio data from the added
     * {@link MediaCodec}'s. The {@link AudioTrack} is used to determine the devices
     * on which the streaming will take place and hence will directly influence the
     * loudness params.
     * <p>Should be called before starting the loudness updates
     * (see {@link #startLoudnessCodecUpdates()},
     * {@link #startLoudnessCodecUpdates(Executor, OnLoudnessCodecUpdateListener)})
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    public void setAudioTrack(@NonNull AudioTrack audioTrack) {
        mAudioTrack = Objects.requireNonNull(audioTrack,
                "AudioTrack for setAudioTrack must not be null");
    }

    /**
     * Gets synchronous loudness updates when no listener is required and at least one
     * {@link MediaCodec} which streams to a registered {@link AudioTrack} is set.
     * Otherwise, an empty {@link Bundle} will be returned.
     *
     * @return the {@link Bundle} containing the current loudness parameters. Caller is
     * responsible to update the {@link MediaCodec}
     *
     * TODO: remove hide once API is final
     * @hide
     */
    @FlaggedApi(FLAG_LOUDNESS_CONFIGURATOR_API)
    @NonNull
    public Bundle getLoudnessCodecParams(@NonNull MediaCodec mediaCodec) {
        // TODO: implement synchronous loudness params updates
        return new Bundle();
    }

    private boolean checkStartLoudnessConfigurator() {
        if (mAudioTrack == null) {
            Log.w(TAG, "Cannot start loudness configurator without an AudioTrack");
            return false;
        }

        if (mMediaCodecs.isEmpty()) {
            Log.w(TAG, "Cannot start loudness configurator without at least one MediaCodec");
            return false;
        }

        return true;
    }
}
