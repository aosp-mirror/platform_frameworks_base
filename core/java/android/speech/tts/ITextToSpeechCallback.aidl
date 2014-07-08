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
package android.speech.tts;

/**
 * Interface for callbacks from TextToSpeechService
 *
 * {@hide}
 */
oneway interface ITextToSpeechCallback {
    /**
     * Tells the client that the synthesis has started.
     *
     * @param utteranceId Unique id identifying synthesis request.
     */
    void onStart(String utteranceId);

    /**
     * Tells the client that the synthesis has finished.
     *
     * @param utteranceId Unique id identifying synthesis request.
     */
    void onSuccess(String utteranceId);

    /**
     * Tells the client that the synthesis was stopped.
     *
     * @param utteranceId Unique id identifying synthesis request.
     */
    void onStop(String utteranceId);

    /**
     * Tells the client that the synthesis has failed.
     *
     * @param utteranceId Unique id identifying synthesis request.
     * @param errorCode One of the values from
     *        {@link android.speech.tts.v2.TextToSpeech}.
     */
    void onError(String utteranceId, int errorCode);

}
