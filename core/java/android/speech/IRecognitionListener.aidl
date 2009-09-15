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
import android.speech.RecognitionResult;

/**
 * Listener for speech recognition events, used with RecognitionService.
 *  This gives you both the final recognition results, as well as various
 *  intermediate events that can be used to show visual feedback to the user.
 *  {@hide}
 */
interface IRecognitionListener {
    /** Called when the endpointer is ready for the user to start speaking. */
    void onReadyForSpeech(in Bundle noiseParams);

    /** The user has started to speak. */
    void onBeginningOfSpeech();

    /** The sound level in the audio stream has changed. */
    void onRmsChanged(in float rmsdB);

    /**
     * More sound has been received. Buffer is a byte buffer containing
     * a sequence of 16-bit shorts. 
     */
    void onBufferReceived(in byte[] buffer);

    /** Called after the user stops speaking. */
    void onEndOfSpeech();

    /**
     * A network or recognition error occurred. The code is defined in
     * {@link android.speech.RecognitionResult}
     */
    void onError(in int error);

    /** 
     * Called when recognition results are ready.
     * @param results: an ordered list of the most likely results (N-best list).
     * @param key: a key associated with the results. The same results can
     * be retrieved asynchronously later using the key, if available. 
     */
    void onResults(in List<RecognitionResult> results, long key);
}
