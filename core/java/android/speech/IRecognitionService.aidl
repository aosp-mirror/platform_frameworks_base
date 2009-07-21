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

import android.content.Intent;
import android.speech.IRecognitionListener;
import android.speech.RecognitionResult;

// A Service interface to speech recognition. Call startListening when
// you want to begin capturing audio; RecognitionService will automatically
// determine when the user has finished speaking, stream the audio to the
// recognition servers, and notify you when results are ready.
/** {@hide} */
interface IRecognitionService {
    // Start listening for speech. Can only call this from one thread at once.
    // see RecognizerIntent.java for constants used to specify the intent.
    void startListening(in Intent recognizerIntent,
        in IRecognitionListener listener);
        
    List<RecognitionResult> getRecognitionResults(in long key);

    void cancel();
}
