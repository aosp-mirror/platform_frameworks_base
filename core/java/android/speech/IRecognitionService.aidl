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
import android.content.Intent;
import android.speech.IRecognitionListener;

/**
* A Service interface to speech recognition. Call startListening when
* you want to begin capturing audio; RecognitionService will automatically
* determine when the user has finished speaking, stream the audio to the
* recognition servers, and notify you when results are ready. In most of the cases, 
* this class should not be used directly, instead use {@link SpeechRecognizer} for
* accessing recognition service. 
* {@hide}
*/
oneway interface IRecognitionService {
    /**
     * Starts listening for speech. Please note that the recognition service supports
     * one listener only, therefore, if this function is called from two different threads,
     * only the latest one will get the notifications
     *
     * @param recognizerIntent the intent from which the invocation occurred. Additionally,
     *        this intent can contain extra parameters to manipulate the behavior of the recognition
     *        client. For more information see {@link RecognizerIntent}.
     * @param listener to receive callbacks, note that this must be non-null
     */
    void startListening(in Intent recognizerIntent, in IRecognitionListener listener);

    /**
     * Stops listening for speech. Speech captured so far will be recognized as
     * if the user had stopped speaking at this point. The function has no effect unless it
     * is called during the speech capturing.
     *
     * @param listener to receive callbacks, note that this must be non-null
     */
    void stopListening(in IRecognitionListener listener);

    /**
     * Cancels the speech recognition.
     *
     * @param listener to receive callbacks, note that this must be non-null
     */
    void cancel(in IRecognitionListener listener);
}
