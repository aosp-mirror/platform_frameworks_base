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
 * Defines additional methods the synthesis callback must implement that
 * are private to the TTS service implementation.
 *
 * All of these class methods (with the exception of {@link #stop()}) can be only called on the
 * synthesis thread, while inside
 * {@link TextToSpeechService#onSynthesizeText} or {@link TextToSpeechService#onSynthesizeTextV2}.
 * {@link #stop()} is the exception, it may be called from multiple threads.
 */
abstract class AbstractSynthesisCallback implements SynthesisCallback {
    /** If true, request comes from V2 TTS interface */
    protected final boolean mClientIsUsingV2;

    /**
     * Constructor.
     * @param clientIsUsingV2 If true, this callback will be used inside
     *         {@link TextToSpeechService#onSynthesizeTextV2} method.
     */
    AbstractSynthesisCallback(boolean clientIsUsingV2) {
        mClientIsUsingV2 = clientIsUsingV2;
    }

    /**
     * Aborts the speech request.
     *
     * Can be called from multiple threads.
     */
    abstract void stop();

    /**
     * Get status code for a "stop".
     *
     * V2 Clients will receive special status, V1 clients will receive standard error.
     *
     * This method should only be called on the synthesis thread,
     * while in {@link TextToSpeechService#onSynthesizeText}.
     */
    int errorCodeOnStop() {
        return mClientIsUsingV2 ? TextToSpeech.STOPPED : TextToSpeech.ERROR;
    }
}
