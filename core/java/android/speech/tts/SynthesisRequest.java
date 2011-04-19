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

/**
 * A request for speech synthesis given to a TTS engine for processing.
 *
 * The engine can provide streaming audio by calling
 * {@link #start}, then {@link #audioAvailable} until all audio has been provided, then finally
 * {@link #done}.
 *
 * Alternatively, the engine can provide all the audio at once, by using
 * {@link #completeAudioAvailable}.
 */
public abstract class SynthesisRequest {

    private final String mText;
    private String mLanguage;
    private String mCountry;
    private String mVariant;
    private int mSpeechRate;
    private int mPitch;

    public SynthesisRequest(String text) {
        mText = text;
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
     * Gets the maximum number of bytes that the TTS engine can pass in a single call of
     * {@link #audioAvailable}. This does not apply to {@link #completeAudioAvailable}.
     */
    public abstract int getMaxBufferSize();

    /**
     * Checks whether the synthesis request completed successfully.
     */
    abstract boolean isDone();

    /**
     * Aborts the speech request.
     *
     * Can be called from multiple threads.
     */
    abstract void stop();

    /**
     * The service should call this when it starts to synthesize audio for this
     * request.
     *
     * This method should only be called on the synthesis thread,
     * while in {@link TextToSpeechService#onSynthesizeText}.
     *
     * @param sampleRateInHz Sample rate in HZ of the generated audio.
     * @param audioFormat Audio format of the generated audio. Must be one of
     *         the ENCODING_ constants defined in {@link android.media.AudioFormat}.
     * @param channelCount The number of channels. Must be {@code 1} or {@code 2}.
     * @return {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
     */
    public abstract int start(int sampleRateInHz, int audioFormat, int channelCount);

    /**
     * The service should call this method when synthesized audio is ready for consumption.
     *
     * This method should only be called on the synthesis thread,
     * while in {@link TextToSpeechService#onSynthesizeText}.
     *
     * @param buffer The generated audio data. This method will not hold on to {@code buffer},
     *         so the caller is free to modify it after this method returns.
     * @param offset The offset into {@code buffer} where the audio data starts.
     * @param length The number of bytes of audio data in {@code buffer}. This must be
     *         less than or equal to the return value of {@link #getMaxBufferSize}.
     * @return {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
     */
    public abstract int audioAvailable(byte[] buffer, int offset, int length);

    /**
     * The service should call this method when all the synthesized audio for a request has
     * been passed to {@link #audioAvailable}.
     *
     * This method should only be called on the synthesis thread,
     * while in {@link TextToSpeechService#onSynthesizeText}.
     *
     * @return {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
     */
    public abstract int done();

    /**
     * The service should call this method if the speech synthesis fails.
     *
     * This method should only be called on the synthesis thread,
     * while in {@link TextToSpeechService#onSynthesizeText}.
     */
    public abstract void error();

    /**
     * The service can call this method instead of using {@link #start}, {@link #audioAvailable}
     * and {@link #done} if all the audio data is available in a single buffer.
     *
     * @param sampleRateInHz Sample rate in HZ of the generated audio.
     * @param audioFormat Audio format of the generated audio. Must be one of
     *         the ENCODING_ constants defined in {@link android.media.AudioFormat}.
     * @param channelCount The number of channels. Must be {@code 1} or {@code 2}.
     * @param buffer The generated audio data. This method will not hold on to {@code buffer},
     *         so the caller is free to modify it after this method returns.
     * @param offset The offset into {@code buffer} where the audio data starts.
     * @param length The number of bytes of audio data in {@code buffer}.
     * @return {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
     */
    public abstract int completeAudioAvailable(int sampleRateInHz, int audioFormat,
            int channelCount, byte[] buffer, int offset, int length);
}