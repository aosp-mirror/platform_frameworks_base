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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.CountDownTimer;
import android.util.Log;

import com.android.systemui.qs.tiles.ScreenRecordTile;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Helper class to initiate a screen recording
 */
@Singleton
public class RecordingController {
    private static final String TAG = "RecordingController";
    private static final String SYSUI_PACKAGE = "com.android.systemui";
    private static final String SYSUI_SCREENRECORD_LAUNCHER =
            "com.android.systemui.screenrecord.ScreenRecordDialog";

    private final Context mContext;
    private boolean mIsStarting;
    private boolean mIsRecording;
    private ScreenRecordTile mTileToUpdate;
    private PendingIntent mStopIntent;
    private CountDownTimer mCountDownTimer = null;

    /**
     * Create a new RecordingController
     * @param context Context for the controller
     */
    @Inject
    public RecordingController(Context context) {
        mContext = context;
    }

    /**
     * Show dialog of screen recording options to user.
     */
    public void launchRecordPrompt(ScreenRecordTile tileToUpdate) {
        final ComponentName launcherComponent = new ComponentName(SYSUI_PACKAGE,
                SYSUI_SCREENRECORD_LAUNCHER);
        final Intent intent = new Intent();
        intent.setComponent(launcherComponent);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("com.android.systemui.screenrecord.EXTRA_SETTINGS_ONLY", true);
        mContext.startActivity(intent);

        mTileToUpdate = tileToUpdate;
    }

    /**
     * Start counting down in preparation to start a recording
     * @param ms Time in ms to count down
     * @param startIntent Intent to start a recording
     * @param stopIntent Intent to stop a recording
     */
    public void startCountdown(long ms, PendingIntent startIntent, PendingIntent stopIntent) {
        mIsStarting = true;
        mStopIntent = stopIntent;

        mCountDownTimer = new CountDownTimer(ms, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                refreshTile(millisUntilFinished);
            }

            @Override
            public void onFinish() {
                mIsStarting = false;
                mIsRecording = true;
                refreshTile();
                try {
                    startIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Pending intent was cancelled: " + e.getMessage());
                }
            }
        };

        mCountDownTimer.start();
    }

    private void refreshTile() {
        refreshTile(0);
    }

    private void refreshTile(long millisUntilFinished) {
        if (mTileToUpdate != null) {
            mTileToUpdate.refreshState(millisUntilFinished);
        } else {
            Log.e(TAG, "No tile to refresh");
        }
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
        refreshTile();
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
    public boolean isRecording() {
        return mIsRecording;
    }

    /**
     * Stop the recording
     */
    public void stopRecording() {
        updateState(false);
        try {
            mStopIntent.send();
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Error stopping: " + e.getMessage());
        }
        refreshTile();
    }

    /**
     * Update the current status
     * @param isRecording
     */
    public void updateState(boolean isRecording) {
        mIsRecording = isRecording;
        refreshTile();
    }
}
