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

package com.android.audiopolicytest;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteCallback;
import android.util.Log;

// This activity will register a dynamic audio policy to intercept media playback and launch
// a thread that will capture audio from the policy mix and crash after the time indicated by
// intent extra "captureDurationMs" has elapsed
public class AudioPolicyDeathTestActivity extends Activity  {
    private static final String TAG = "AudioPolicyDeathTestActivity";

    private static final int SAMPLE_RATE = 48000;
    private static final int RECORD_TIME_MS = 1000;

    private AudioManager mAudioManager = null;
    private AudioPolicy mAudioPolicy = null;

    public AudioPolicyDeathTestActivity() {
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mAudioManager = getApplicationContext().getSystemService(AudioManager.class);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        AudioMixingRule.Builder audioMixingRuleBuilder = new AudioMixingRule.Builder()
                .addRule(attributes, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);

        AudioFormat audioFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setSampleRate(SAMPLE_RATE)
                .build();

        AudioMix audioMix = new AudioMix.Builder(audioMixingRuleBuilder.build())
                .setFormat(audioFormat)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK)
                .build();

        AudioPolicy.Builder audioPolicyBuilder = new AudioPolicy.Builder(getApplicationContext());
        audioPolicyBuilder.addMix(audioMix)
                .setLooper(Looper.getMainLooper());
        mAudioPolicy = audioPolicyBuilder.build();

        int result = mAudioManager.registerAudioPolicy(mAudioPolicy);
        if (result == AudioManager.SUCCESS) {
            AudioRecord audioRecord = mAudioPolicy.createAudioRecordSink(audioMix);
            if (audioRecord != null && audioRecord.getState() != AudioRecord.STATE_UNINITIALIZED) {
                int captureDurationMs = getIntent().getIntExtra(
                                getString(R.string.capture_duration_key), RECORD_TIME_MS);
                AudioCapturingThread thread =
                        new AudioCapturingThread(audioRecord, captureDurationMs);
                thread.start();
            } else {
                Log.w(TAG, "AudioRecord creation failed");
                result = AudioManager.ERROR_NO_INIT;
            }
        } else {
            Log.w(TAG, "registerAudioPolicy failed, status: " + result);
        }

        RemoteCallback cb =
                (RemoteCallback) getIntent().getExtras().get(getString(R.string.callback_key));
        Bundle res = new Bundle();
        res.putInt(getString(R.string.status_key), result);
        Log.i(TAG, "policy " + (result ==  AudioManager.SUCCESS ? "" : "un")
                + "successfully registered");
        cb.sendResult(res);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAudioManager != null && mAudioPolicy != null) {
            mAudioManager.unregisterAudioPolicy(mAudioPolicy);
        }
    }

    // A thread that captures audio from the supplied AudioRecord and crashes after the supplied
    // duration has elapsed
    private static class AudioCapturingThread extends Thread {
        private final AudioRecord mAudioRecord;
        private final int mDurationMs;

        AudioCapturingThread(AudioRecord record, int durationMs) {
            super();
            mAudioRecord = record;
            mDurationMs = durationMs;
        }

        @Override
        @SuppressWarnings("ConstantOverflow")
        public void run() {
            int samplesLeft = mDurationMs * SAMPLE_RATE * mAudioRecord.getChannelCount() / 1000;
            short[] readBuffer = new short[samplesLeft / 10];
            mAudioRecord.startRecording();
            long startTimeMs = System.currentTimeMillis();
            long elapsedTimeMs = 0;
            do {
                int read = readBuffer.length < samplesLeft ? readBuffer.length : samplesLeft;
                read = mAudioRecord.read(readBuffer, 0, read);
                elapsedTimeMs = System.currentTimeMillis() - startTimeMs;
                if (read < 0) {
                    Log.w(TAG, "read error: " + read);
                    break;
                }
                samplesLeft -= read;
            } while (elapsedTimeMs < mDurationMs && samplesLeft > 0);

            // force process to crash
            int i = 1 / 0;
        }
    }
}
