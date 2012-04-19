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
 * Acoustic Echo Canceler (AEC).
 * <p>Acoustic Echo Canceler (AEC) is an audio pre-processing which removes the contribution of the
 * signal received from the remote party from the captured audio signal.
 * <p>AEC is used by voice communication applications (voice chat, video conferencing, SIP calls)
 * where the presence of echo with significant delay in the signal received from the remote party
 * is highly disturbing. AEC is often used in conjunction with noise suppression (NS).
 * <p>An application creates an AcousticEchoCanceler object to instantiate and control an AEC
 * engine in the audio capture path.
 * <p>To attach the AcousticEchoCanceler to a particular {@link android.media.AudioRecord},
 * specify the audio session ID of this AudioRecord when creating the AcousticEchoCanceler.
 * The audio session is retrieved by calling
 * {@link android.media.AudioRecord#getAudioSessionId()} on the AudioRecord instance.
 * <p>On some devices, an AEC can be inserted by default in the capture path by the platform
 * according to the {@link android.media.MediaRecorder.AudioSource} used. The application should
 * call AcousticEchoCanceler.getEnable() after creating the AEC to check the default AEC activation
 * state on a particular AudioRecord session.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on
 * controlling audio effects.
 */

public class AcousticEchoCanceler extends AudioEffect {

    private final static String TAG = "AcousticEchoCanceler";

    /**
     * Checks if the device implements acoustic echo cancellation.
     * @return true if the device implements acoustic echo cancellation, false otherwise.
     */
    public static boolean isAvailable() {
        return AudioEffect.isEffectTypeAvailable(AudioEffect.EFFECT_TYPE_AEC);
    }

    /**
     * Creates an AcousticEchoCanceler and attaches it to the AudioRecord on the audio
     * session specified.
     * @param audioSession system wide unique audio session identifier. The AcousticEchoCanceler
     * will be applied to the AudioRecord with the same audio session.
     * @return AcousticEchoCanceler created or null if the device does not implement AEC.
     */
    public static AcousticEchoCanceler create(int audioSession) {
        AcousticEchoCanceler aec = null;
        try {
            aec = new AcousticEchoCanceler(audioSession);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "not implemented on this device"+ aec);
        } catch (UnsupportedOperationException e) {
            Log.w(TAG, "not enough resources");
        } catch (RuntimeException e) {
            Log.w(TAG, "not enough memory");
        } finally {
            return aec;
        }
    }

    /**
     * Class constructor.
     * <p> The constructor is not guarantied to succeed and throws the following exceptions:
     * <ul>
     *  <li>IllegalArgumentException is thrown if the device does not implement an AEC</li>
     *  <li>UnsupportedOperationException is thrown is the resources allocated to audio
     *  pre-procesing are currently exceeded.</li>
     *  <li>RuntimeException is thrown if a memory allocation error occurs.</li>
     * </ul>
     *
     * @param audioSession system wide unique audio session identifier. The AcousticEchoCanceler
     * will be applied to the AudioRecord with the same audio session.
     *
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    private AcousticEchoCanceler(int audioSession)
            throws IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_AEC, EFFECT_TYPE_NULL, 0, audioSession);
    }
}
