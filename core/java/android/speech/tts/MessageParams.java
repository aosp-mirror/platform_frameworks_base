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

import android.speech.tts.TextToSpeechService.UtteranceCompletedDispatcher;

abstract class MessageParams {
    private final UtteranceCompletedDispatcher mDispatcher;

    static final int TYPE_SYNTHESIS = 1;
    static final int TYPE_AUDIO = 2;
    static final int TYPE_SILENCE = 3;

    MessageParams(UtteranceCompletedDispatcher dispatcher) {
        mDispatcher = dispatcher;
    }

    UtteranceCompletedDispatcher getDispatcher() {
        return mDispatcher;
    }

    abstract int getType();
}
