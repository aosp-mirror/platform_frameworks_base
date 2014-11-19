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

package com.android.speech.tts;

import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeechService;
import android.util.Log;

import java.util.ArrayList;
import java.util.logging.Logger;

public class MockableTextToSpeechService extends TextToSpeechService {

    private static IDelegate sDelegate;

    public static void setMocker(IDelegate delegate) {
        sDelegate = delegate;
    }

    static IDelegate getMocker() {
        return sDelegate;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        return sDelegate.onIsLanguageAvailable(lang, country, variant);
    }

    @Override
    protected String[] onGetLanguage() {
        return sDelegate.onGetLanguage();
    }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        return sDelegate.onLoadLanguage(lang, country, variant);
    }

    @Override
    protected void onStop() {
        sDelegate.onStop();
    }

    @Override
    protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        sDelegate.onSynthesizeText(request, callback);
    }

    public static interface IDelegate {
        int onIsLanguageAvailable(String lang, String country, String variant);

        String[] onGetLanguage();

        int onLoadLanguage(String lang, String country, String variant);

        void onStop();

        void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback);

        ArrayList<String> getAvailableVoices();

        ArrayList<String> getUnavailableVoices();
    }

}
