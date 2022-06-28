/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Intent;
import android.os.Bundle;

/**
 * Used for receiving notifications from the SpeechRecognizer when the
 * recognition related events occur. All the callbacks are executed on the
 * Application main thread.
 */
public interface RecognitionListener {

    /**
     * Called when the endpointer is ready for the user to start speaking.
     * 
     * @param params parameters set by the recognition service. Reserved for future use.
     */
    void onReadyForSpeech(Bundle params);

    /**
     * The user has started to speak.
     */
    void onBeginningOfSpeech();

    /**
     * The sound level in the audio stream has changed. There is no guarantee that this method will
     * be called.
     * 
     * @param rmsdB the new RMS dB value
     */
    void onRmsChanged(float rmsdB);

    /**
     * More sound has been received. The purpose of this function is to allow giving feedback to the
     * user regarding the captured audio. There is no guarantee that this method will be called.
     * 
     * @param buffer a buffer containing a sequence of big-endian 16-bit integers representing a
     *        single channel audio stream. The sample rate is implementation dependent.
     */
    void onBufferReceived(byte[] buffer);

    /**
     * Called after the user stops speaking.
     */
    void onEndOfSpeech();

    /**
     * A network or recognition error occurred.
     * 
     * @param error code is defined in {@link SpeechRecognizer}. Implementations need to handle any
     *              integer error constant to be passed here beyond constants prefixed with ERROR_.
     */
    void onError(@SpeechRecognizer.RecognitionError int error);

    /**
     * Called when recognition results are ready.
     *
     * <p>
     *     Called with the results for the full speech since {@link #onReadyForSpeech(Bundle)}.
     *     To get recognition results in segments rather than for the full session see
     *     {@link RecognizerIntent#EXTRA_SEGMENTED_SESSION}.
     * </p>
     *
     * @param results the recognition results. To retrieve the results in {@code
     *        ArrayList<String>} format use {@link Bundle#getStringArrayList(String)} with
     *        {@link SpeechRecognizer#RESULTS_RECOGNITION} as a parameter. A float array of
     *        confidence values might also be given in {@link SpeechRecognizer#CONFIDENCE_SCORES}.
     */
    void onResults(Bundle results);

    /**
     * Called when partial recognition results are available. The callback might be called at any
     * time between {@link #onBeginningOfSpeech()} and {@link #onResults(Bundle)} when partial
     * results are ready. This method may be called zero, one or multiple times for each call to
     * {@link SpeechRecognizer#startListening(Intent)}, depending on the speech recognition
     * service implementation.  To request partial results, use
     * {@link RecognizerIntent#EXTRA_PARTIAL_RESULTS}
     * 
     * @param partialResults the returned results. To retrieve the results in
     *        ArrayList&lt;String&gt; format use {@link Bundle#getStringArrayList(String)} with
     *        {@link SpeechRecognizer#RESULTS_RECOGNITION} as a parameter
     */
    void onPartialResults(Bundle partialResults);

    /**
     * Called for each ready segment of a recognition request. To request segmented speech results
     * use {@link RecognizerIntent#EXTRA_SEGMENTED_SESSION}. The callback might be called
     * any number of times between {@link #onReadyForSpeech(Bundle)} and
     * {@link #onEndOfSegmentedSession()}.
     *
     * @param segmentResults the returned results. To retrieve the results in
     *        ArrayList&lt;String&gt; format use {@link Bundle#getStringArrayList(String)} with
     *        {@link SpeechRecognizer#RESULTS_RECOGNITION} as a parameter
     */
    default void onSegmentResults(@NonNull Bundle segmentResults) {}

    /**
     * Called at the end of a segmented recognition request. To request segmented speech results
     * use {@link RecognizerIntent#EXTRA_SEGMENTED_SESSION}.
     */
    default void onEndOfSegmentedSession() {}

    /**
     * Reserved for adding future events.
     * 
     * @param eventType the type of the occurred event
     * @param params a Bundle containing the passed parameters
     */
    void onEvent(int eventType, Bundle params);
}
