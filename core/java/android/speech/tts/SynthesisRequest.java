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
 * Contains data required by engines to synthesize speech. This data is:
 * <ul>
 *   <li>The text to synthesize</li>
 *   <li>The synthesis locale, represented as a language, country and a variant.
 *   The language is an ISO 639-3 letter language code, and the country is an
 *   ISO 3166 alpha 3 code. The variant is not specified.</li>
 *   <li>The name of the voice requested for this synthesis. May be empty if
 *   the client uses {@link TextToSpeech#setLanguage} instead of
 *   {@link TextToSpeech#setVoice}</li>
 *   <li>The synthesis speech rate, with 100 being the normal, and
 *   higher values representing higher speech rates.</li>
 *   <li>The voice pitch, with 100 being the default pitch.</li>
 * </ul>
 *
 * Any additional parameters sent to the text to speech service are passed in
 * uninterpreted, see the {@code params} argument in {@link TextToSpeech#speak}
 * and {@link TextToSpeech#synthesizeToFile}.
 */
public final class SynthesisRequest {
    private final CharSequence mText;
    private final Bundle mParams;
    private String mVoiceName;
    private String mLanguage;
    private String mCountry;
    private String mVariant;
    private int mSpeechRate;
    private int mPitch;
    private int mCallerUid;

    public SynthesisRequest(String text, Bundle params) {
        mText = text;
        // Makes a copy of params.
        mParams = new Bundle(params);
    }

    public SynthesisRequest(CharSequence text, Bundle params) {
        mText = text;
        // Makes a copy of params.
        mParams = new Bundle(params);
    }

    /**
     * Gets the text which should be synthesized.
     * @deprecated As of API level 21, replaced by {@link #getCharSequenceText}.
     */
    @Deprecated
    public String getText() {
        return mText.toString();
    }

    /**
     * Gets the text which should be synthesized.
     */
    public CharSequence getCharSequenceText() {
        return mText;
    }

    /**
     * Gets the name of the voice to use.
     */
    public String getVoiceName() {
        return mVoiceName;
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
     * Gets the request caller Uid.
     */
    public int getCallerUid() {
        return mCallerUid;
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
     * Sets the voice name for the request.
     */
    void setVoiceName(String voiceName) {
        mVoiceName = voiceName;
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

    /**
     * Sets Caller Uid
     */
    void setCallerUid(int uid) {
        mCallerUid = uid;
    }
}
