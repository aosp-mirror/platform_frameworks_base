/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.speech;

import android.os.Bundle;

/**
 *  Listener for speech recognition events, used with RecognitionService.
 *  This gives you both the final recognition results, as well as various
 *  intermediate events that can be used to show visual feedback to the user.
 *  {@hide}
 */
oneway interface IRecognitionListener {
    /**
     * Called when the endpointer is ready for the user to start speaking.
     *
     * @param params parameters set by the recognition service. Reserved for future use.
     */
    void onReadyForSpeech(in Bundle params);

    /**
     * The user has started to speak.
     */
    void onBeginningOfSpeech();

    /**
     * The sound level in the audio stream has changed.
     *
     * @param rmsdB the new RMS dB value
     */
    void onRmsChanged(in float rmsdB);

    /**
     * More sound has been received.
     *
     * @param buffer the byte buffer containing a sequence of 16-bit shorts.
     */
    void onBufferReceived(in byte[] buffer);

    /**
     * Called after the user stops speaking.
     */
    void onEndOfSpeech();

    /**
     * A network or recognition error occurred.
     *
     * @param error code is defined in {@link SpeechRecognizer}
     */
    void onError(in int error);

    /**
     * Called when recognition results are ready.
     *
     * @param results a Bundle containing the most likely results (N-best list).
     */
    void onResults(in Bundle results);

     /**
     * Called when recognition partial results are ready.
     *
     * @param results a Bundle containing the current most likely result.
     */
    void onPartialResults(in Bundle results);

    /**
     * Reserved for adding future events.
     *
     * @param eventType the type of the occurred event
     * @param params a Bundle containing the passed parameters
     */
    void onEvent(in int eventType, in Bundle params);
}
