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
import android.service.voice.VoiceInteractionService;
import android.util.Log;

import java.util.Arrays;

public class MainInteractionService extends VoiceInteractionService {
    static final String TAG = "MainInteractionService";

    private final Callback mHotwordCallback = new Callback() {
        @Override
        public void onDetected(byte[] data) {
            Log.i(TAG, "onDetected");
        }

        @Override
        public void onDetectionStarted() {
            Log.i(TAG, "onDetectionStarted");
        }

        @Override
        public void onDetectionStopped() {
            Log.i(TAG, "onDetectionStopped");
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

        mHotwordDetector = getAlwaysOnHotwordDetector("Hello There", "en-US", mHotwordCallback);
        int availability = mHotwordDetector.getAvailability();
        Log.i(TAG, "Hotword availability = " + availability);

        switch (availability) {
            case AlwaysOnHotwordDetector.KEYPHRASE_HARDWARE_UNAVAILABLE:
                Log.i(TAG, "KEYPHRASE_HARDWARE_UNAVAILABLE");
                break;
            case AlwaysOnHotwordDetector.KEYPHRASE_UNSUPPORTED:
                Log.i(TAG, "KEYPHRASE_UNSUPPORTED");
                break;
            case AlwaysOnHotwordDetector.KEYPHRASE_UNENROLLED:
                Log.i(TAG, "KEYPHRASE_UNENROLLED");
                Intent enroll = mHotwordDetector.getManageIntent(
                        AlwaysOnHotwordDetector.MANAGE_ACTION_ENROLL);
                Log.i(TAG, "Need to enroll with " + enroll);
                break;
            case AlwaysOnHotwordDetector.KEYPHRASE_ENROLLED:
                Log.i(TAG, "KEYPHRASE_ENROLLED");
                int status = mHotwordDetector.startRecognition(
                        AlwaysOnHotwordDetector.RECOGNITION_FLAG_NONE);
                Log.i(TAG, "startRecognition status = " + status);
                break;
        }
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
