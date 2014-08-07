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
import android.service.voice.AlwaysOnHotwordDetector;
import android.service.voice.AlwaysOnHotwordDetector.Callback;
import android.service.voice.AlwaysOnHotwordDetector.EventPayload;
import android.service.voice.VoiceInteractionService;
import android.util.Log;

import java.util.Arrays;

public class MainInteractionService extends VoiceInteractionService {
    static final String TAG = "MainInteractionService";

    private final Callback mHotwordCallback = new Callback() {
        @Override
        public void onAvailabilityChanged(int status) {
            Log.i(TAG, "onAvailabilityChanged(" + status + ")");
            hotwordAvailabilityChangeHelper(status);
        }

        @Override
        public void onDetected(EventPayload eventPayload) {
            Log.i(TAG, "onDetected");
        }

        @Override
        public void onError() {
            Log.i(TAG, "onError");
        }
    };

    private AlwaysOnHotwordDetector mHotwordDetector;

    @Override
    public void onReady() {
        super.onReady();
        Log.i(TAG, "Creating " + this);
        Log.i(TAG, "Keyphrase enrollment error? " + getKeyphraseEnrollmentInfo().getParseError());
        Log.i(TAG, "Keyphrase enrollment meta-data: "
                + Arrays.toString(getKeyphraseEnrollmentInfo().listKeyphraseMetadata()));

        mHotwordDetector = createAlwaysOnHotwordDetector("Hello There", "en-US", mHotwordCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle args = new Bundle();
        args.putParcelable("intent", new Intent(this, TestInteractionActivity.class));
        startSession(args);
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    private void hotwordAvailabilityChangeHelper(int availability) {
        Log.i(TAG, "Hotword availability = " + availability);
        switch (availability) {
            case AlwaysOnHotwordDetector.STATE_HARDWARE_UNAVAILABLE:
                Log.i(TAG, "STATE_HARDWARE_UNAVAILABLE");
                break;
            case AlwaysOnHotwordDetector.STATE_KEYPHRASE_UNSUPPORTED:
                Log.i(TAG, "STATE_KEYPHRASE_UNSUPPORTED");
                break;
            case AlwaysOnHotwordDetector.STATE_KEYPHRASE_UNENROLLED:
                Log.i(TAG, "STATE_KEYPHRASE_UNENROLLED");
                Intent enroll = mHotwordDetector.getManageIntent(
                        AlwaysOnHotwordDetector.MANAGE_ACTION_ENROLL);
                Log.i(TAG, "Need to enroll with " + enroll);
                break;
            case AlwaysOnHotwordDetector.STATE_KEYPHRASE_ENROLLED:
                Log.i(TAG, "STATE_KEYPHRASE_ENROLLED - starting recognition");
                if (mHotwordDetector.startRecognition(
                        AlwaysOnHotwordDetector.RECOGNITION_FLAG_NONE)) {
                    Log.i(TAG, "startRecognition succeeded");
                } else {
                    Log.i(TAG, "startRecognition failed");
                }
                break;
        }
    }
}
