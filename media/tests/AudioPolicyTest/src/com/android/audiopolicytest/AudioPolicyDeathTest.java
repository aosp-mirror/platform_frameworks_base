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
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.platform.test.annotations.Presubmit;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class AudioPolicyDeathTest {
    private static final String TAG = "AudioPolicyDeathTest";

    private static final int SAMPLE_RATE = 48000;
    private static final int PLAYBACK_TIME_MS = 4000;
    private static final int RECORD_TIME_MS = 1000;
    private static final int ACTIVITY_TIMEOUT_SEC = 5;
    private static final int BROADCAST_TIMEOUT_SEC = 10;
    private static final int MAX_ATTEMPTS = 5;
    private static final int DELAY_BETWEEN_ATTEMPTS_MS = 2000;

    private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
            new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    private class MyBroadcastReceiver extends BroadcastReceiver {
        private CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                mLatch.countDown();
            }
        }

        public void reset() {
            mLatch = new CountDownLatch(1);
        }

        public boolean waitForBroadcast() {
            boolean received = false;
            long startTimeMs = System.currentTimeMillis();
            long elapsedTimeMs = 0;

            Log.i(TAG, "waiting for broadcast");

            while (elapsedTimeMs < BROADCAST_TIMEOUT_SEC && !received) {
                try {
                    received = mLatch.await(BROADCAST_TIMEOUT_SEC, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Log.w(TAG, "wait interrupted");
                }
                elapsedTimeMs = System.currentTimeMillis() - startTimeMs;
            }
            Log.i(TAG, "broadcast " + (received ? "" : "NOT ") + "received");
            return received;
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

        boolean result = false;
        for (int numAttempts = 1; numAttempts <= MAX_ATTEMPTS && !result; numAttempts++) {
            mReceiver.reset();

            CompletableFuture<Integer> callbackReturn = new CompletableFuture<>();
            RemoteCallback cb = new RemoteCallback((Bundle res) -> {
                callbackReturn.complete(
                        res.getInt(mContext.getResources().getString(R.string.status_key)));
            });

            // Launch process registering a dynamic audio policy and dying after RECORD_TIME_MS ms
            // RECORD_TIME_MS must be shorter than PLAYBACK_TIME_MS
            Intent intent = new Intent(mContext, AudioPolicyDeathTestActivity.class);
            intent.putExtra(mContext.getResources().getString(R.string.capture_duration_key),
                    RECORD_TIME_MS);
            intent.putExtra(mContext.getResources().getString(R.string.callback_key), cb);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            mContext.startActivity(intent);

            Integer status = AudioManager.ERROR;
            try {
                status = callbackReturn.get(ACTIVITY_TIMEOUT_SEC, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                assumeNoException(e);
            }
            assumeTrue(status != null && status == AudioManager.SUCCESS);

            Log.i(TAG, "Activity started");
            AudioTrack track = null;
            try {
                track = createAudioTrack();
                track.play();
                result = mReceiver.waitForBroadcast();
            } finally {
                if (track != null) {
                    track.stop();
                    track.release();
                }
            }
            if (!result) {
                try {
                    Log.i(TAG, "Retrying after attempt: " + numAttempts);
                    Thread.sleep(DELAY_BETWEEN_ATTEMPTS_MS);
                } catch (InterruptedException e) {
                }
            }
        }
        assertTrue(result);
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
