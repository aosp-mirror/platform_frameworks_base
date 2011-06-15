/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.speech.tts;

import android.os.Bundle;

/**
 * Contains data required by engines to synthesize speech. This data is :
 * <ul>
 *   <li>The text to synthesize</li>
 *   <li>The synthesis locale, represented as a language, country and a variant.
 *   The language is an ISO 639-3 letter language code, and the country is an
 *   ISO 3166 alpha 3 code. The variant is not specified.</li>
 *   <li>The synthesis speech rate, with 100 being the normal, and
 *   higher values representing higher speech rates.</li>
 *   <li>The voice pitch, with 100 being the default pitch.</li>
 * </ul>
 *
 * Any additional parameters sent to the text to speech service are passed in
 * uninterpreted, see the @code{params} argument in {@link TextToSpeech#speak}
 * and {@link TextToSpeech#synthesizeToFile}.
 */
public final class SynthesisRequest {
    private final String mText;
    private final Bundle mParams;
    private String mLanguage;
    private String mCountry;
    private String mVariant;
    private int mSpeechRate;
    private int mPitch;

    public SynthesisRequest(String text, Bundle params) {
        mText = text;
        // Makes a copy of params.
        mParams = new Bundle(params);
    }

    /**
     * Gets the text which should be synthesized.
     */
    public String getText() {
        return mText;
    }

    /**
     * Gets the ISO 3-letter language code for the language to use.
     */
    public String getLanguage() {
        return mLanguage;
    }

    /**
     * Gets the ISO 3-letter country code for the language to use.
     */
    public String getCountry() {
        return mCountry;
    }

    /**
     * Gets the language variant to use.
     */
    public String getVariant() {
        return mVariant;
    }

    /**
     * Gets the speech rate to use. The normal rate is 100.
     */
    public int getSpeechRate() {
        return mSpeechRate;
    }

    /**
     * Gets the pitch to use. The normal pitch is 100.
     */
    public int getPitch() {
        return mPitch;
    }

    /**
     * Gets the additional params, if any.
     */
    public Bundle getParams() {
        return mParams;
    }

    /**
     * Sets the locale for the request.
     */
    void setLanguage(String language, String country, String variant) {
        mLanguage = language;
        mCountry = country;
        mVariant = variant;
    }

    /**
     * Sets the speech rate.
     */
    void setSpeechRate(int speechRate) {
        mSpeechRate = speechRate;
    }

    /**
     * Sets the pitch.
     */
    void setPitch(int pitch) {
        mPitch = pitch;
    }
}
