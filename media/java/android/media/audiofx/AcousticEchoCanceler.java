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
 * specify the audio session ID of this AudioRecord when constructing the AcousticEchoCanceler.
 * The audio session is retrieved by calling
 * {@link android.media.AudioRecord#getAudioSessionId()} on the AudioRecord instance.
 * <p>On some devices, an AEC can be inserted by default in the capture path by the platform
 * according to the {@link android.media.MediaRecorder.AudioSource} used. The application can
 * query which pre-processings are currently applied to an AudioRecord instance by calling
 * {@link android.media.audiofx.AudioEffect#queryPreProcessings(int)} with the audio session of the
 * AudioRecord.
 * <p>See {@link android.media.audiofx.AudioEffect} class for more details on
 * controlling audio effects.
 * @hide
 */

public class AcousticEchoCanceler extends AudioEffect {

    private final static String TAG = "AcousticEchoCanceler";

    /**
     * Class constructor.
     * <p> The application must catch exceptions when creating an AcousticEchoCanceler as the
     * constructor is not guarantied to succeed:
     * <ul>
     *  <li>IllegalArgumentException is thrown if the device does not implement an AEC</li>
     *  <li>UnsupportedOperationException is thrown is the resources allocated to audio
     *  pre-procesing are currently exceeded.</li>
     * </ul>
     *
     * @param audioSession system wide unique audio session identifier. The AcousticEchoCanceler
     * will be applied to the AudioRecord with the same audio session.
     *
     * @throws java.lang.IllegalArgumentException
     * @throws java.lang.UnsupportedOperationException
     * @throws java.lang.RuntimeException
     */
    public AcousticEchoCanceler(int audioSession)
            throws IllegalArgumentException, UnsupportedOperationException, RuntimeException {
        super(EFFECT_TYPE_AEC, EFFECT_TYPE_NULL, 0, audioSession);
    }
}
