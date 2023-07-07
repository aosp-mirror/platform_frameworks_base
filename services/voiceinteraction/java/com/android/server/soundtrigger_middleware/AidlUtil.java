/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseRecognitionExtra;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionStatus;
import android.media.soundtrigger.SoundModelType;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;

/**
 * Utilities for working with sound trigger related AIDL generated types.
 */
public class AidlUtil {
    /**
     * Initialize a new recognition event.
     * @return The new event.
     */
    static RecognitionEvent newEmptyRecognitionEvent() {
        RecognitionEvent result = new RecognitionEvent();
        result.data = new byte[0];
        return result;
    }

    /**
     * Initialize a new phrase recognition event.
     * @return The new event.
     */
    static PhraseRecognitionEvent newEmptyPhraseRecognitionEvent() {
        PhraseRecognitionEvent result = new PhraseRecognitionEvent();
        result.common = newEmptyRecognitionEvent();
        result.phraseExtras = new PhraseRecognitionExtra[0];
        return result;
    }

    /**
     * Creates a new generic abort event.
     *
     * @return The new event.
     */
    static RecognitionEventSys newAbortEvent() {
        RecognitionEvent recognitionEvent = newEmptyRecognitionEvent();
        recognitionEvent.type = SoundModelType.GENERIC;
        recognitionEvent.status = RecognitionStatus.ABORTED;
        RecognitionEventSys recognitionEventSys = new RecognitionEventSys();
        recognitionEventSys.recognitionEvent = recognitionEvent;
        return recognitionEventSys;
    }

    /**
     * Creates a new generic phrase event.
     *
     * @return The new event.
     */
    static PhraseRecognitionEventSys newAbortPhraseEvent() {
        PhraseRecognitionEvent recognitionEvent = newEmptyPhraseRecognitionEvent();
        recognitionEvent.common.type = SoundModelType.KEYPHRASE;
        recognitionEvent.common.status = RecognitionStatus.ABORTED;
        PhraseRecognitionEventSys phraseRecognitionEventSys = new PhraseRecognitionEventSys();
        phraseRecognitionEventSys.phraseRecognitionEvent = recognitionEvent;
        return phraseRecognitionEventSys;
    }
}
