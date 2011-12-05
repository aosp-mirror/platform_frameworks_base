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
import android.util.Log;

class SilencePlaybackQueueItem extends PlaybackQueueItem {
    private final ConditionVariable mCondVar = new ConditionVariable();
    private final long mSilenceDurationMs;

    SilencePlaybackQueueItem(UtteranceProgressDispatcher dispatcher,
            Object callerIdentity, long silenceDurationMs) {
        super(dispatcher, callerIdentity);
        mSilenceDurationMs = silenceDurationMs;
    }

    @Override
    public void run() {
        getDispatcher().dispatchOnStart();
        if (mSilenceDurationMs > 0) {
            mCondVar.block(mSilenceDurationMs);
        }
        getDispatcher().dispatchOnDone();
    }

    @Override
    void stop(boolean isError) {
        mCondVar.open();
    }
}
