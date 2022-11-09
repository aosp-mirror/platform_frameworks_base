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

import android.app.BroadcastOptions;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.CallbackController;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Helper class to initiate a screen recording
 */
@SysUISingleton
public class RecordingController
        implements CallbackController<RecordingController.RecordingStateChangeCallback> {
    private static final String TAG = "RecordingController";

    private boolean mIsStarting;
    private boolean mIsRecording;
    private PendingIntent mStopIntent;
    private final Bundle mInteractiveBroadcastOption;
    private CountDownTimer mCountDownTimer = null;
    private final Executor mMainExecutor;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserContextProvider mUserContextProvider;
    private final UserTracker mUserTracker;

    protected static final String INTENT_UPDATE_STATE =
            "com.android.systemui.screenrecord.UPDATE_STATE";
    protected static final String EXTRA_STATE = "extra_state";

    private CopyOnWriteArrayList<RecordingStateChangeCallback> mListeners =
            new CopyOnWriteArrayList<>();

    @VisibleForTesting
    final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    stopRecording();
                }
            };

    @VisibleForTesting
    protected final BroadcastReceiver mStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && INTENT_UPDATE_STATE.equals(intent.getAction())) {
                if (intent.hasExtra(EXTRA_STATE)) {
                    boolean state = intent.getBooleanExtra(EXTRA_STATE, false);
                    updateState(state);
                } else {
                    Log.e(TAG, "Received update intent with no state");
                }
            }
        }
    };

    /**
     * Create a new RecordingController
     */
    @Inject
    public RecordingController(@Main Executor mainExecutor,
            BroadcastDispatcher broadcastDispatcher,
            UserContextProvider userContextProvider,
            UserTracker userTracker) {
        mMainExecutor = mainExecutor;
        mBroadcastDispatcher = broadcastDispatcher;
        mUserContextProvider = userContextProvider;
        mUserTracker = userTracker;

        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setInteractive(true);
        mInteractiveBroadcastOption = options.toBundle();
    }

    /** Create a dialog to show screen recording options to the user. */
    public Dialog createScreenRecordDialog(Context context, FeatureFlags flags,
                                           DialogLaunchAnimator dialogLaunchAnimator,
                                           ActivityStarter activityStarter,
                                           @Nullable Runnable onStartRecordingClicked) {
        return flags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING)
                ? new ScreenRecordPermissionDialog(context, this, activityStarter,
                        dialogLaunchAnimator, mUserContextProvider, onStartRecordingClicked)
                : new ScreenRecordDialog(context, this, activityStarter,
                mUserContextProvider, flags, dialogLaunchAnimator, onStartRecordingClicked);
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
                    startIntent.send(mInteractiveBroadcastOption);
                    mUserTracker.addCallback(mUserChangedCallback, mMainExecutor);

                    IntentFilter stateFilter = new IntentFilter(INTENT_UPDATE_STATE);
                    mBroadcastDispatcher.registerReceiver(mStateChangeReceiver, stateFilter, null,
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
                mStopIntent.send(mInteractiveBroadcastOption);
            } else {
                Log.e(TAG, "Stop intent was null");
            }
            updateState(false);
        } catch (PendingIntent.CanceledException e) {
            Log.e(TAG, "Error stopping: " + e.getMessage());
        }
    }

    /**
     * Update the current status
     * @param isRecording
     */
    public synchronized void updateState(boolean isRecording) {
        if (!isRecording && mIsRecording) {
            // Unregister receivers if we have stopped recording
            mUserTracker.removeCallback(mUserChangedCallback);
            mBroadcastDispatcher.unregisterReceiver(mStateChangeReceiver);
        }
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
    public void addCallback(@NonNull RecordingStateChangeCallback listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeCallback(@NonNull RecordingStateChangeCallback listener) {
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
