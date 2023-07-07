/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.audiofx;

import android.annotation.NonNull;
import android.media.AudioManager;
import android.util.Log;

import java.util.UUID;

/**
 * Haptic Generator(HG).
 * <p>HG is an audio post-processor which generates haptic data based on the audio channels. The
 * generated haptic data is sent along with audio data down to the audio HAL, which will require the
 * device to support audio-coupled-haptic playback. In that case, the effect will only be created on
 * device supporting audio-coupled-haptic playback. Call {@link HapticGenerator#isAvailable()} to
 * check if the device supports this effect.
 * <p>An application can create a HapticGenerator object to initiate and control this audio effect
 * in the audio framework.
 * <p>To attach the HapticGenerator to a particular AudioTrack or MediaPlayer, specify the audio
 * session ID of this AudioTrack or MediaPlayer when constructing the HapticGenerator.
 * <p>See {@link android.media.MediaPlayer#getAudioSessionId()} for details on audio sessions.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on controlling audio
 * effects.
 */
public class HapticGenerator extends AudioEffect implements AutoCloseable {

    private static final String TAG = "HapticGenerator";

    // For every HapticGenerator, it contains a volume control effect so that the volume control
    // will always be handled in the effect chain. In that case, the HapticGenerator can generate
    // haptic data based on the raw audio data.
    private AudioEffect mVolumeControlEffect;

    /**
     * @return true if the HapticGenerator is available on the device.
     */
    public static boolean isAvailable() {
        return AudioManager.isHapticPlaybackSupported()
                && AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_HAPTIC_GENERATOR);
    }

    /**
     * Creates a HapticGenerator and attaches it to the given audio session.
     * Use {@link android.media.AudioTrack#getAudioSessionId()} or
     * {@link android.media.MediaPlayer#getAudioSessionId()} to
     * apply this effect on specific AudioTrack or MediaPlayer instance.
     *
     * @param audioSession system wide unique audio session identifier. The HapticGenerator will be
     *                     applied to the players with the same audio session.
     * @return HapticGenerator created or null if the device does not support HapticGenerator or
     *                         the audio session is invalid.
     * @throws java.lang.IllegalArgumentException when HapticGenerator is not supported
     * @throws java.lang.UnsupportedOperationException when the effect library is not loaded.
     * @throws java.lang.RuntimeException for all other error
     */
    public static @NonNull HapticGenerator create(int audioSession) {
        return new HapticGenerator(audioSession);
    }

    /**
     * Class constructor.
     *
     * @param audioSession system wide unique audio session identifier. The HapticGenerator will be
     *                     attached to the MediaPlayer or AudioTrack in the same audio session.
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    private HapticGenerator(int audioSession) {
        super(EFFECT_TYPE_HAPTIC_GENERATOR, EFFECT_TYPE_NULL, 0, audioSession);
        mVolumeControlEffect = new AudioEffect(
                AudioEffect.EFFECT_TYPE_NULL,
                UUID.fromString("119341a0-8469-11df-81f9-0002a5d5c51b"),
                0,
                audioSession);
    }

    /**
     * Enable or disable the effect. The effect can only be enabled if the caller has the
     * {@link android.Manifest.permission#VIBRATE} permission.
     *
     * @param enabled the requested enable state
     * @return {@link #SUCCESS} in case of success, {@link #ERROR_INVALID_OPERATION}
     *         or {@link #ERROR_DEAD_OBJECT} in case of failure.
     */
    @Override
    public int setEnabled(boolean enabled) {
        int ret = super.setEnabled(enabled);
        if (ret == SUCCESS) {
            if (mVolumeControlEffect == null
                    || mVolumeControlEffect.setEnabled(enabled) != SUCCESS) {
                Log.w(TAG, "Failed to enable volume control effect for HapticGenerator");
            }
        }
        return ret;
    }

    /**
     * Releases the native AudioEffect resources.
     */
    @Override
    public void release() {
        if (mVolumeControlEffect != null) {
            mVolumeControlEffect.release();
        }
        super.release();
    }

    /**
     * Release the resources that are held by the effect.
     */
    @Override
    public void close() {
        release();
    }
}
