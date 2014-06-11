/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.test.voiceinteraction;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

import java.util.Arrays;

public class MainInteractionService extends VoiceInteractionService {
    static final String TAG = "MainInteractionService";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Creating " + this);
        Log.i(TAG, "Keyphrase enrollment error? " + getKeyphraseEnrollmentInfo().getParseError());
        Log.i(TAG, "Keyphrase enrollment meta-data: "
                + Arrays.toString(getKeyphraseEnrollmentInfo().getKeyphrases()));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle args = new Bundle();
        args.putParcelable("intent", new Intent(this, TestInteractionActivity.class));
        startSession(args);
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
