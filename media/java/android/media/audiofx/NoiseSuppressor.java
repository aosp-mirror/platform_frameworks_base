/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.util.Log;

/**
 * Noise Suppressor (NS).
 * <p>Noise suppression (NS) is an audio pre-processor which removes background noise from the
 * captured signal. The component of the signal considered as noise can be either stationary
 * (car/airplane engine, AC system) or non-stationary (other peoples conversations, car horn) for
 * more advanced implementations.
 * <p>NS is mostly used by voice communication applications (voice chat, video conferencing,
 * SIP calls).
 * <p>An application creates a NoiseSuppressor object to instantiate and control an NS
 * engine in the audio framework.
 * <p>To attach the NoiseSuppressor to a particular {@link android.media.AudioRecord},
 * specify the audio session ID of this AudioRecord when creating the NoiseSuppressor.
 * The audio session is retrieved by calling
 * {@link android.media.AudioRecord#getAudioSessionId()} on the AudioRecord instance.
 * <p>On some devices, NS can be inserted by default in the capture path by the platform
 * according to the {@link android.media.MediaRecorder.AudioSource} used. The application should
 * call NoiseSuppressor.getEnable() after creating the NS to check the default NS activation
 * state on a particular AudioRecord session.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on
 * controlling audio effects.
 */

public class NoiseSuppressor extends AudioEffect {

    private final static String TAG = "NoiseSuppressor";

    /**
     * Checks if the device implements noise suppression.
     * @return true if the device implements noise suppression, false otherwise.
     */
    public static boolean isAvailable() {
        return AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_NS);
    }

    /**
     * Creates a NoiseSuppressor and attaches it to the AudioRecord on the audio
     * session specified.
     * @param audioSession system wide unique audio session identifier. The NoiseSuppressor
     * will be applied to the AudioRecord with the same audio session.
     * @return NoiseSuppressor created or null if the device does not implement noise
     * suppression.
     */
    public static NoiseSuppressor create(int audioSession) {
        NoiseSuppressor ns = null;
        try {
            ns = new NoiseSuppressor(audioSession);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "not implemented on this device "+ns);
        } catch (UnsupportedOperationException e) {
            Log.w(TAG, "not enough resources");
        } catch (RuntimeException e) {
            Log.w(TAG, "not enough memory");
        }
        return ns;
    }

    /**
     * Class constructor.
     * <p> The constructor is not guarantied to succeed and throws the following exceptions:
     * <ul>
     *  <li>IllegalArgumentException is thrown if the device does not implement an NS</li>
     *  <li>UnsupportedOperationException is thrown is the resources allocated to audio
     *  pre-procesing are currently exceeded.</li>
     *  <li>RuntimeException is thrown if a memory allocation error occurs.</li>
     * </ul>
     *
     * @param audioSession system wide unique audio session identifier. The NoiseSuppressor
     * will be applied to the AudioRecord with the same audio session.
     *
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    private NoiseSuppressor(int audioSession)
            throws IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_NS, EFFECT_TYPE_NULL, 0, audioSession);
    }
}
