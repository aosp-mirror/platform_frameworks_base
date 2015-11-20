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
     * Tells the client that the synthesis has started playing.
     *
     * @param utteranceId Unique id identifying the synthesis request.
     */
    void onStart(String utteranceId);

    /**
     * Tells the client that the synthesis has finished playing.
     *
     * @param utteranceId Unique id identifying the synthesis request.
     */
    void onSuccess(String utteranceId);

    /**
     * Tells the client that the synthesis was stopped.
     *
     * @param utteranceId Unique id identifying the synthesis request.
     */
    void onStop(String utteranceId, boolean isStarted);

    /**
     * Tells the client that the synthesis has failed.
     *
     * @param utteranceId Unique id identifying the synthesis request.
     * @param errorCode One of the values from
     *        {@link android.speech.tts.v2.TextToSpeech}.
     */
    void onError(String utteranceId, int errorCode);

    /**
     * Tells the client that the TTS engine has started synthesizing the audio for a request.
     *
     * <p>
     * This doesn't mean the synthesis request has already started playing (for example when there
     * are synthesis requests ahead of it in the queue), but after receiving this callback you can
     * expect onAudioAvailable to be called.
     * </p>
     *
     * @param utteranceId Unique id identifying the synthesis request.
     * @param sampleRateInHz Sample rate in HZ of the generated audio.
     * @param audioFormat The audio format of the generated audio in the {@link #onAudioAvailable}
     *        call. Should be one of {@link android.media.AudioFormat.ENCODING_PCM_8BIT},
     *        {@link android.media.AudioFormat.ENCODING_PCM_16BIT} or
     *        {@link android.media.AudioFormat.ENCODING_PCM_FLOAT}.
     * @param channelCount The number of channels.
     */
    void onBeginSynthesis(String utteranceId, int sampleRateInHz, int audioFormat, int channelCount);

    /**
     * Tells the client about a chunk of the synthesized audio.
     *
     * <p>
     * Called when a chunk of the synthesized audio is ready. This may be called more than once for
     * every synthesis request, thereby streaming the audio to the client.
     * </p>
     *
     * @param utteranceId Unique id identifying the synthesis request.
     * @param audio The raw audio bytes. Its format is specified by the {@link #onStartAudio}
     * callback.
     */
    void onAudioAvailable(String utteranceId, in byte[] audio);
}
