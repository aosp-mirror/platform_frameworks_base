/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * The HwAudioSource represents the audio playback directly from a source audio device.
 * It currently supports {@link HwAudioSource#start()} and {@link HwAudioSource#stop()} only
 * corresponding to {@link AudioSystem#startAudioSource(AudioPortConfig, AudioAttributes)}
 * and {@link AudioSystem#stopAudioSource(int)}.
 *
 * @hide
 */
@SystemApi
public class HwAudioSource extends PlayerBase {
    private final AudioDeviceInfo mAudioDeviceInfo;
    private final AudioAttributes mAudioAttributes;

    /**
     * The value of the native handle encodes the HwAudioSource state.
     * The native handle returned by {@link AudioSystem#startAudioSource} is either valid
     * (aka > 0, so successfully started) or hosting an error code (negative).
     * 0 corresponds to an untialized or stopped HwAudioSource.
     */
    private int mNativeHandle = 0;

    /**
     * Class constructor for a hardware audio source based player.
     *
     * Use the {@link Builder} class to construct a {@link HwAudioSource} instance.
     *
     * @param device {@link AudioDeviceInfo} instance of the source audio device.
     * @param attributes {@link AudioAttributes} instance for this player.
     */
    private HwAudioSource(@NonNull AudioDeviceInfo device, @NonNull AudioAttributes attributes) {
        super(attributes, AudioPlaybackConfiguration.PLAYER_TYPE_HW_SOURCE);
        Preconditions.checkNotNull(device);
        Preconditions.checkNotNull(attributes);
        Preconditions.checkArgument(device.isSource(), "Requires a source device");
        mAudioDeviceInfo = device;
        mAudioAttributes = attributes;
        baseRegisterPlayer(AudioSystem.AUDIO_SESSION_ALLOCATE);
    }

    /**
     * TODO: sets the gain on {@link #mAudioDeviceInfo}.
     *
     * @param muting if true, the player is to be muted, and the volume values can be ignored
     * @param leftVolume the left volume to use if muting is false
     * @param rightVolume the right volume to use if muting is false
     */
    @Override
    void playerSetVolume(boolean muting, float leftVolume, float rightVolume) {
    }

    /**
     * TODO: applies {@link VolumeShaper} on {@link #mAudioDeviceInfo}.
     *
     * @param configuration a {@code VolumeShaper.Configuration} object
     *        created by {@link VolumeShaper.Configuration.Builder} or
     *        an created from a {@code VolumeShaper} id
     *        by the {@link VolumeShaper.Configuration} constructor.
     * @param operation a {@code VolumeShaper.Operation}.
     * @return
     */
    @Override
    int playerApplyVolumeShaper(
            @NonNull VolumeShaper.Configuration configuration,
            @NonNull VolumeShaper.Operation operation) {
        return 0;
    }

    /**
     * TODO: gets the {@link VolumeShaper} by a given id.
     *
     * @param id the {@code VolumeShaper} id returned from
     *           sending a fully specified {@code VolumeShaper.Configuration}
     *           through {@link #playerApplyVolumeShaper}
     * @return
     */
    @Override
    @Nullable
    VolumeShaper.State playerGetVolumeShaperState(int id) {
        return new VolumeShaper.State(1f, 1f);
    }

    /**
     * TODO: sets the level on {@link #mAudioDeviceInfo}.
     *
     * @param muting
     * @param level
     * @return
     */
    @Override
    int playerSetAuxEffectSendLevel(boolean muting, float level) {
        return AudioSystem.SUCCESS;
    }

    @Override
    void playerStart() {
        start();
    }

    @Override
    void playerPause() {
        // Pause is equivalent to stop for hardware audio source based players.
        stop();
    }

    @Override
    void playerStop() {
        stop();
    }

    /**
     * Starts the playback from {@link AudioDeviceInfo}.
     * Starts does not return any error code, caller must check {@link HwAudioSource#isPlaying} to
     * ensure the state of the HwAudioSource encoded in {@link mNativeHandle}.
     */
    public void start() {
        Preconditions.checkState(!isPlaying(), "HwAudioSource is currently playing");
        mNativeHandle = AudioSystem.startAudioSource(
                mAudioDeviceInfo.getPort().activeConfig(),
                mAudioAttributes);
        if (isPlaying()) {
            // FIXME: b/174876389 clean up device id reporting
            baseStart(getDeviceId());
        }
    }

    private int getDeviceId() {
        ArrayList<AudioPatch> patches = new ArrayList<AudioPatch>();
        if (AudioManager.listAudioPatches(patches) != AudioManager.SUCCESS) {
            return 0;
        }

        for (int i = 0; i < patches.size(); i++) {
            AudioPatch patch = patches.get(i);
            AudioPortConfig[] sources = patch.sources();
            AudioPortConfig[] sinks = patch.sinks();
            if ((sources != null) && (sources.length > 0)) {
                for (int c = 0;  c < sources.length; c++) {
                    if (sources[c].port().id() == mAudioDeviceInfo.getId()) {
                        return sinks[c].port().id();
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Checks whether the HwAudioSource player is playing.
     * It checks the state of the HwAudioSource encoded in {@link HwAudioSource#isPlaying}.
     * 0 corresponds to a stopped or uninitialized HwAudioSource.
     * Negative value corresponds to a status reported by {@link AudioSystem#startAudioSource} to
     * indicate a failure when trying to start the HwAudioSource.
     *
     * @return true if currently playing, false otherwise
     */
    public boolean isPlaying() {
        return mNativeHandle > 0;
    }

    /**
     * Stops the playback from {@link AudioDeviceInfo}.
     */
    public void stop() {
        if (mNativeHandle > 0) {
            baseStop();
            AudioSystem.stopAudioSource(mNativeHandle);
            mNativeHandle = 0;
        }
    }

    /**
     * Builder class for {@link HwAudioSource} objects.
     * Use this class to configure and create a <code>HwAudioSource</code> instance.
     * <p>Here is an example where <code>Builder</code> is used to specify an audio
     * playback directly from a source device as media usage, to be used by a new
     * <code>HwAudioSource</code> instance:
     *
     * <pre class="prettyprint">
     * HwAudioSource player = new HwAudioSource.Builder()
     *              .setAudioAttributes(new AudioAttributes.Builder()
     *                       .setUsage(AudioAttributes.USAGE_MEDIA)
     *                       .build())
     *              .setAudioDeviceInfo(device)
     *              .build()
     * </pre>
     * <p>
     * If the audio attributes are not set with {@link #setAudioAttributes(AudioAttributes)},
     * attributes comprising {@link AudioAttributes#USAGE_MEDIA} will be used.
     */
    public static final class Builder {
        private AudioAttributes mAudioAttributes;
        private AudioDeviceInfo mAudioDeviceInfo;

        /**
         * Constructs a new Builder with default values.
         */
        public Builder() {
        }

        /**
         * Sets the {@link AudioAttributes}.
         * @param attributes a non-null {@link AudioAttributes} instance that describes the audio
         *     data to be played.
         * @return the same Builder instance.
         */
        public @NonNull Builder setAudioAttributes(@NonNull AudioAttributes attributes) {
            Preconditions.checkNotNull(attributes);
            mAudioAttributes = attributes;
            return this;
        }

        /**
         * Sets the {@link AudioDeviceInfo}.
         * @param info a non-null {@link AudioDeviceInfo} instance that describes the audio
         *     data come from.
         * @return the same Builder instance.
         */
        public @NonNull Builder setAudioDeviceInfo(@NonNull AudioDeviceInfo info) {
            Preconditions.checkNotNull(info);
            Preconditions.checkArgument(info.isSource());
            mAudioDeviceInfo = info;
            return this;
        }

        /**
         * Builds an {@link HwAudioSource} instance initialized with all the parameters set
         * on this <code>Builder</code>.
         * @return a new successfully initialized {@link HwAudioSource} instance.
         */
        public @NonNull HwAudioSource build() {
            Preconditions.checkNotNull(mAudioDeviceInfo);
            if (mAudioAttributes == null) {
                mAudioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build();
            }
            return new HwAudioSource(mAudioDeviceInfo, mAudioAttributes);
        }
    }
}
