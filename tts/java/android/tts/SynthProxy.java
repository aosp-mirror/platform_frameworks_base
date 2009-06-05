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
    public void stop() {
        native_stop(mJniData);
    }

    /**
     * Synthesize speech and speak it directly using AudioTrack.
     */
    public void speak(String text) {
        native_speak(mJniData, text);
    }

    /**
     * Synthesize speech to a file. The current implementation writes a valid
     * WAV file to the given path, assuming it is writable. Something like
     * "/sdcard/???.wav" is recommended.
     */
    public void synthesizeToFile(String text, String filename) {
        native_synthesizeToFile(mJniData, text, filename);
    }

    // TODO add IPA methods

    /**
     * Sets the language
     */
    public void setLanguage(String language) {
        native_setLanguage(mJniData, language);
    }

    /**
     * Sets the speech rate
     */
    public final void setSpeechRate(int speechRate) {
        native_setSpeechRate(mJniData, speechRate);
    }


    /**
     * Plays the given audio buffer
     */
    public void playAudioBuffer(int bufferPointer, int bufferSize) {
        native_playAudioBuffer(mJniData, bufferPointer, bufferSize);
    }

    /**
     * Gets the currently set language
     */
    public String getLanguage() {
        return native_getLanguage(mJniData);
    }

    /**
     * Gets the currently set rate
     */
    public int getRate() {
        return native_getRate(mJniData);
    }

    /**
     * Shuts down the native synthesizer
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

    private native final void native_stop(int jniData);

    private native final void native_speak(int jniData, String text);

    private native final void native_synthesizeToFile(int jniData, String text, String filename);

    private native final void native_setLanguage(int jniData, String language);

    private native final void native_setSpeechRate(int jniData, int speechRate);

    // TODO add buffer format
    private native final void native_playAudioBuffer(int jniData, int bufferPointer, int bufferSize);

    private native final String native_getLanguage(int jniData);

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
