/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.tts;

import android.media.AudioManager;
import android.media.AudioSystem;
import android.util.Log;
import java.lang.ref.WeakReference;

/**
 * @hide
 *
 * The SpeechSynthesis class provides a high-level api to create and play
 * synthesized speech. This class is used internally to talk to a native
 * TTS library that implements the interface defined in
 * frameworks/base/include/tts/TtsEngine.h
 *
 */
@SuppressWarnings("unused")
public class SynthProxy {

    //
    // External API
    //

    /**
     * Constructor; pass the location of the native TTS .so to use.
     */
    public SynthProxy(String nativeSoLib) {
        Log.e("TTS is loading", nativeSoLib);
        native_setup(new WeakReference<SynthProxy>(this), nativeSoLib);
    }

    /**
     * Stops and clears the AudioTrack.
     */
    public int stop() {
        return native_stop(mJniData);
    }

    /**
     * Synthesize speech and speak it directly using AudioTrack.
     */
    public int speak(String text, int streamType) {
        if ((streamType > -1) && (streamType < AudioSystem.getNumStreamTypes())) {
            return native_speak(mJniData, text, streamType);
        } else {
            Log.e("SynthProxy", "Trying to speak with invalid stream type " + streamType);
            return native_speak(mJniData, text, AudioManager.STREAM_MUSIC);
        }
    }

    /**
     * Synthesize speech to a file. The current implementation writes a valid
     * WAV file to the given path, assuming it is writable. Something like
     * "/sdcard/???.wav" is recommended.
     */
    public int synthesizeToFile(String text, String filename) {
        return native_synthesizeToFile(mJniData, text, filename);
    }

    /**
     * Queries for language support.
     * Return codes are defined in android.speech.tts.TextToSpeech
     */
    public int isLanguageAvailable(String language, String country, String variant) {
        return native_isLanguageAvailable(mJniData, language, country, variant);
    }

    /**
     * Sets the language.
     */
    public int setLanguage(String language, String country, String variant) {
        return native_setLanguage(mJniData, language, country, variant);
    }

    /**
     * Loads the language: it's not set, but prepared for use later.
     */
    public int loadLanguage(String language, String country, String variant) {
        return native_loadLanguage(mJniData, language, country, variant);
    }

    /**
     * Sets the speech rate.
     */
    public final int setSpeechRate(int speechRate) {
        return native_setSpeechRate(mJniData, speechRate);
    }

    /**
     * Sets the pitch of the synthesized voice.
     */
    public final int setPitch(int pitch) {
        return native_setPitch(mJniData, pitch);
    }

    /**
     * Returns the currently set language, country and variant information.
     */
    public String[] getLanguage() {
        return native_getLanguage(mJniData);
    }

    /**
     * Gets the currently set rate.
     */
    public int getRate() {
        return native_getRate(mJniData);
    }

    /**
     * Shuts down the native synthesizer.
     */
    public void shutdown()  {
        native_shutdown(mJniData);
    }

    //
    // Internal
    //

    protected void finalize() {
        native_finalize(mJniData);
        mJniData = 0;
    }

    static {
        System.loadLibrary("ttssynthproxy");
    }

    private final static String TAG = "SynthProxy";

    /**
     * Accessed by native methods
     */
    private int mJniData = 0;

    private native final void native_setup(Object weak_this,
            String nativeSoLib);

    private native final void native_finalize(int jniData);

    private native final int native_stop(int jniData);

    private native final int native_speak(int jniData, String text, int streamType);

    private native final int native_synthesizeToFile(int jniData, String text, String filename);

    private native final int  native_isLanguageAvailable(int jniData, String language,
            String country, String variant);

    private native final int native_setLanguage(int jniData, String language, String country,
            String variant);

    private native final int native_loadLanguage(int jniData, String language, String country,
            String variant);

    private native final int native_setSpeechRate(int jniData, int speechRate);

    private native final int native_setPitch(int jniData, int speechRate);

    private native final String[] native_getLanguage(int jniData);

    private native final int native_getRate(int jniData);

    private native final void native_shutdown(int jniData);


    /**
     * Callback from the C layer
     */
    @SuppressWarnings("unused")
    private static void postNativeSpeechSynthesizedInJava(Object tts_ref,
            int bufferPointer, int bufferSize) {

        Log.i("TTS plugin debug", "bufferPointer: " + bufferPointer
                + " bufferSize: " + bufferSize);

        SynthProxy nativeTTS = (SynthProxy)((WeakReference)tts_ref).get();
        // TODO notify TTS service of synthesis/playback completion,
        //      method definition to be changed.
    }
}
