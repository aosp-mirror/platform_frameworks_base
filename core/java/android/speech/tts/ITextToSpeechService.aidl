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

import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.ITextToSpeechCallback;

/**
 * Interface for TextToSpeech to talk to TextToSpeechService.
 *
 * {@hide}
 */
interface ITextToSpeechService {

    /**
     * Tells the engine to synthesize some speech and play it back.
     *
     * @param callingInstance a binder representing the identity of the calling
     *        TextToSpeech object.
     * @param text The text to synthesize.
     * @param queueMode Determines what to do to requests already in the queue.
     * @param param Request parameters.
     */
    int speak(in IBinder callingInstance, in String text, in int queueMode, in Bundle params);

    /**
     * Tells the engine to synthesize some speech and write it to a file.
     *
     * @param callingInstance a binder representing the identity of the calling
     *        TextToSpeech object.
     * @param text The text to synthesize.
     * @param filename The file to write the synthesized audio to.
     * @param param Request parameters.
     */
    int synthesizeToFile(in IBinder callingInstance, in String text,
        in String filename, in Bundle params);

    /**
     * Plays an existing audio resource.
     *
     * @param callingInstance a binder representing the identity of the calling
     *        TextToSpeech object.
     * @param audioUri URI for the audio resource (a file or android.resource URI)
     * @param queueMode Determines what to do to requests already in the queue.
     * @param param Request parameters.
     */
    int playAudio(in IBinder callingInstance, in Uri audioUri, in int queueMode, in Bundle params);

    /**
     * Plays silence.
     *
     * @param callingInstance a binder representing the identity of the calling
     *        TextToSpeech object.
     * @param duration Number of milliseconds of silence to play.
     * @param queueMode Determines what to do to requests already in the queue.
     * @param param Request parameters.
     */
    int playSilence(in IBinder callingInstance, in long duration, in int queueMode, in Bundle params);

    /**
     * Checks whether the service is currently playing some audio.
     */
    boolean isSpeaking();

    /**
     * Interrupts the current utterance (if from the given app) and removes any utterances
     * in the queue that are from the given app.
     *
     * @param callingInstance a binder representing the identity of the calling
     *        TextToSpeech object.
     */
    int stop(in IBinder callingInstance);

    /**
     * Returns the language, country and variant currently being used by the TTS engine.
     *
     * Can be called from multiple threads.
     *
     * @return A 3-element array, containing language (ISO 3-letter code),
     *         country (ISO 3-letter code) and variant used by the engine.
     *         The country and variant may be {@code ""}. If country is empty, then variant must
     *         be empty too.
     */
    String[] getLanguage();

    /**
     * Checks whether the engine supports a given language.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return Code indicating the support status for the locale.
     *         One of {@link TextToSpeech#LANG_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_VAR_AVAILABLE},
     *         {@link TextToSpeech#LANG_MISSING_DATA}
     *         {@link TextToSpeech#LANG_NOT_SUPPORTED}.
     */
    int isLanguageAvailable(in String lang, in String country, in String variant);

    /**
     * Returns a list of features available for a given language. Elements of the returned
     * string array can be passed in as keys to {@link TextToSpeech#speak} and
     * {@link TextToSpeech#synthesizeToFile} to select a given feature or features to be
     * used during synthesis.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return An array of strings containing the set of features supported for
     *         the supplied locale. The array of strings must not contain 
     *         duplicates.
     */
    String[] getFeaturesForLanguage(in String lang, in String country, in String variant);

    /**
     * Notifies the engine that it should load a speech synthesis language.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return Code indicating the support status for the locale.
     *         One of {@link TextToSpeech#LANG_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_VAR_AVAILABLE},
     *         {@link TextToSpeech#LANG_MISSING_DATA}
     *         {@link TextToSpeech#LANG_NOT_SUPPORTED}.
     */
    int loadLanguage(in String lang, in String country, in String variant);

    /**
     * Sets the callback that will be notified when playback of utterance from the
     * given app are completed.
     *
     * @param callingApp Package name for the app whose utterance the callback will handle.
     * @param cb The callback.
     */
    void setCallback(in IBinder caller, ITextToSpeechCallback cb);

}
