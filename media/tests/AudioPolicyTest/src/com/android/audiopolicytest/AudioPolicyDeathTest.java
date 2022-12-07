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

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AudioPolicyDeathTest {
    private static final String TAG = "AudioPolicyDeathTest";

    private static final int SAMPLE_RATE = 48000;
    private static final int PLAYBACK_TIME_MS = 2000;

    private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
            new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    private class MyBroadcastReceiver extends BroadcastReceiver {
        private boolean mReceived = false;
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                synchronized (this) {
                    mReceived = true;
                    notify();
                }
            }
        }

        public synchronized boolean received() {
            return mReceived;
        }
    }
    private final MyBroadcastReceiver mReceiver = new MyBroadcastReceiver();

    private Context mContext;

    @Before
    public void setUp() {
        mContext = getApplicationContext();
        assertEquals(PackageManager.PERMISSION_GRANTED,
                mContext.checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_ROUTING));
    }

    //-----------------------------------------------------------------
    // Tests that an AUDIO_BECOMING_NOISY intent is broadcast when an app having registered
    // a dynamic audio policy that intercepts an active media playback dies
    //-----------------------------------------------------------------
    @Test
    public void testPolicyClientDeathSendBecomingNoisyIntent() {
        mContext.registerReceiver(mReceiver, AUDIO_NOISY_INTENT_FILTER);

        // Launch process registering a dynamic auido policy and dying after PLAYBACK_TIME_MS/2 ms
        Intent intent = new Intent(mContext, AudioPolicyDeathTestActivity.class);
        intent.putExtra("captureDurationMs", PLAYBACK_TIME_MS / 2);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mContext.startActivity(intent);

        AudioTrack track = createAudioTrack();
        track.play();
        synchronized (mReceiver) {
            long startTimeMs = System.currentTimeMillis();
            long elapsedTimeMs = 0;
            while (elapsedTimeMs < PLAYBACK_TIME_MS && !mReceiver.received()) {
                try {
                    mReceiver.wait(PLAYBACK_TIME_MS - elapsedTimeMs);
                } catch (InterruptedException e) {
                    Log.w(TAG, "wait interrupted");
                }
                elapsedTimeMs = System.currentTimeMillis() - startTimeMs;
            }
        }

        track.stop();
        track.release();

        assertTrue(mReceiver.received());
    }

    private AudioTrack createAudioTrack() {
        AudioFormat format = new AudioFormat.Builder()
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .build();

        short[] data = new short[PLAYBACK_TIME_MS * SAMPLE_RATE * format.getChannelCount() / 1000];
        AudioAttributes attributes =
                new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

        AudioTrack track = new AudioTrack(attributes, format, data.length,
                AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.write(data, 0, data.length);

        return track;
    }
}
