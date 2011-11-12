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

import android.os.ConditionVariable;
import android.speech.tts.TextToSpeechService.UtteranceProgressDispatcher;

class SilenceMessageParams extends MessageParams {
    private final ConditionVariable mCondVar = new ConditionVariable();
    private final long mSilenceDurationMs;

    SilenceMessageParams(UtteranceProgressDispatcher dispatcher,
            String callingApp, long silenceDurationMs) {
        super(dispatcher, callingApp);
        mSilenceDurationMs = silenceDurationMs;
    }

    long getSilenceDurationMs() {
        return mSilenceDurationMs;
    }

    @Override
    int getType() {
        return TYPE_SILENCE;
    }

    ConditionVariable getConditionVariable() {
        return mCondVar;
    }

}
