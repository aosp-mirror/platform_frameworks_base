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

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;
import java.util.List;

public class MockableCheckVoiceData extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        MockableTextToSpeechService.IDelegate delegate =
                MockableTextToSpeechService.getMocker();

        ArrayList<String> availableLangs = delegate.getAvailableVoices();
        ArrayList<String> unavailableLangs = delegate.getUnavailableVoices();

        final Intent returnVal = new Intent();

        // Returns early.
        if (availableLangs == null) {
            setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL, returnVal);
            finish();
            return;
        }

        returnVal.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                    availableLangs);

        if (unavailableLangs != null && unavailableLangs.size() > 0) {
            returnVal.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                    unavailableLangs);
        }

        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, returnVal);
        finish();
    }

}
