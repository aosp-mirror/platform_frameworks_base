/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.screenrecord;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.CountDownTimer;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class to initiate a screen recording
 */
@Singleton
public class RecordingController
        implements CallbackController<RecordingController.RecordingStateChangeCallback> {
    private static final String TAG = "RecordingController";
    private static final String SYSUI_PACKAGE = "com.android.systemui";
    private static final String SYSUI_SCREENRECORD_LAUNCHER =
            "com.android.systemui.screenrecord.ScreenRecordDialog";

    private boolean mIsStarting;
    private boolean mIsRecording;
    private PendingIntent mStopIntent;
    private CountDownTimer mCountDownTimer = null;
    private BroadcastDispatcher mBroadcastDispatcher;

    private ArrayList<RecordingStateChangeCallback> mListeners = new ArrayList<>();

    @VisibleForTesting
    protected final BroadcastReceiver mUserChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mStopIntent != null) {
                stopRecording();
            }
        }
    };

    /**
     * Create a new RecordingController
     */
    @Inject
    public RecordingController(BroadcastDispatcher broadcastDispatcher) {
        mBroadcastDispatcher = broadcastDispatcher;
    }

    /**
     * Get an intent to show screen recording options to the user.
     */
    public Intent getPromptIntent() {
        final ComponentName launcherComponent = new ComponentName(SYSUI_PACKAGE,
                SYSUI_SCREENRECORD_LAUNCHER);
        final Intent intent = new Intent();
        intent.setComponent(launcherComponent);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /**
     * Start counting down in preparation to start a recording
     * @param ms Total time in ms to wait before starting
     * @param interval Time in ms per countdown step
     * @param startIntent Intent to start a recording
     * @param stopIntent Intent to stop a recording
     */
    public void startCountdown(long ms, long interval, PendingIntent startIntent,
            PendingIntent stopIntent) {
        mIsStarting = true;
        mStopIntent = stopIntent;

        mCountDownTimer = new CountDownTimer(ms, interval) {
            @Override
            public void onTick(long millisUntilFinished) {
                for (RecordingStateChangeCallback cb : mListeners) {
                    cb.onCountdown(millisUntilFinished);
                }
            }

            @Override
            public void onFinish() {
                mIsStarting = false;
                mIsRecording = true;
                for (RecordingStateChangeCallback cb : mListeners) {
                    cb.onCountdownEnd();
                }
                try {
                    startIntent.send();
                    IntentFilter userFilter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
                    mBroadcastDispatcher.registerReceiver(mUserChangeReceiver, userFilter, null,
                            UserHandle.ALL);
                    Log.d(TAG, "sent start intent");
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Pending intent was cancelled: " + e.getMessage());
                }
            }
        };

        mCountDownTimer.start();
    }

    /**
     * Cancel a countdown in progress. This will not stop the recording if it already started.
     */
    public void cancelCountdown() {
        if (mCountDownTimer != null) {
            mCountDownTimer.cancel();
        } else {
            Log.e(TAG, "Timer was null");
        }
        mIsStarting = false;

        for (RecordingStateChangeCallback cb : mListeners) {
            cb.onCountdownEnd();
        }
    }

    /**
     * Check if the recording is currently counting down to begin
     * @return
     */
    public boolean isStarting() {
        return mIsStarting;
    }

    /**
     * Check if the recording is ongoing
     * @return
     */
    public synchronized boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Stop the recording
     */
    public void stopRecording() {
        try {
            if (mStopIntent != null) {
                mStopIntent.send();
            } else {
                Log.e(TAG, "Stop intent was null");
            }
            updateState(false);
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Error stopping: " + e.getMessage());
        }
        mBroadcastDispatcher.unregisterReceiver(mUserChangeReceiver);
    }

    /**
     * Update the current status
     * @param isRecording
     */
    public synchronized void updateState(boolean isRecording) {
        mIsRecording = isRecording;
        for (RecordingStateChangeCallback cb : mListeners) {
            if (isRecording) {
                cb.onRecordingStart();
            } else {
                cb.onRecordingEnd();
            }
        }
    }

    @Override
    public void addCallback(RecordingStateChangeCallback listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeCallback(RecordingStateChangeCallback listener) {
        mListeners.remove(listener);
    }

    /**
     * A callback for changes in the screen recording state
     */
    public interface RecordingStateChangeCallback {
        /**
         * Called when a countdown to recording has updated
         *
         * @param millisUntilFinished Time in ms remaining in the countdown
         */
        default void onCountdown(long millisUntilFinished) {}

        /**
         * Called when a countdown to recording has ended. This is a separate method so that if
         * needed, listeners can handle cases where recording fails to start
         */
        default void onCountdownEnd() {}

        /**
         * Called when a screen recording has started
         */
        default void onRecordingStart() {}

        /**
         * Called when a screen recording has ended
         */
        default void onRecordingEnd() {}
    }
}
