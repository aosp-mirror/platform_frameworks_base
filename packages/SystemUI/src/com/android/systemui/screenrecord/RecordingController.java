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
import android.media.projection.StopReason;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Process;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger;
import com.android.systemui.mediaprojection.SessionCreationSource;
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver;
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDisabledDialogDelegate;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.CallbackController;

import dagger.Lazy;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Helper class to initiate a screen recording
 */
@SysUISingleton
public class RecordingController
        implements CallbackController<RecordingController.RecordingStateChangeCallback> {
    private boolean mIsStarting;
    private boolean mIsRecording;
    private PendingIntent mStopIntent;
    private @StopReason int mStopReason = StopReason.STOP_UNKNOWN;
    private final Bundle mInteractiveBroadcastOption;
    private CountDownTimer mCountDownTimer = null;
    private final Executor mMainExecutor;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UserTracker mUserTracker;
    private final RecordingControllerLogger mRecordingControllerLogger;
    private final MediaProjectionMetricsLogger mMediaProjectionMetricsLogger;
    private final ScreenCaptureDisabledDialogDelegate mScreenCaptureDisabledDialogDelegate;
    private final ScreenRecordPermissionDialogDelegate.Factory
            mScreenRecordPermissionDialogDelegateFactory;

    protected static final String INTENT_UPDATE_STATE =
            "com.android.systemui.screenrecord.UPDATE_STATE";
    protected static final String EXTRA_STATE = "extra_state";

    private final CopyOnWriteArrayList<RecordingStateChangeCallback> mListeners =
            new CopyOnWriteArrayList<>();

    private final Lazy<ScreenCaptureDevicePolicyResolver> mDevicePolicyResolver;

    @VisibleForTesting
    final UserTracker.Callback mUserChangedCallback =
            new UserTracker.Callback() {
                @Override
                public void onUserChanged(int newUser, @NonNull Context userContext) {
                    stopRecording(StopReason.STOP_USER_SWITCH);
                }
            };

    @VisibleForTesting
    protected final BroadcastReceiver mStateChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && INTENT_UPDATE_STATE.equals(intent.getAction())) {
                if (intent.hasExtra(EXTRA_STATE)) {
                    boolean state = intent.getBooleanExtra(EXTRA_STATE, false);
                    mRecordingControllerLogger.logIntentStateUpdated(state);
                    updateState(state);
                } else {
                    mRecordingControllerLogger.logIntentMissingState();
                }
            }
        }
    };

    /**
     * Create a new RecordingController
     */
    @Inject
    public RecordingController(
            @Main Executor mainExecutor,
            BroadcastDispatcher broadcastDispatcher,
            Lazy<ScreenCaptureDevicePolicyResolver> devicePolicyResolver,
            UserTracker userTracker,
            RecordingControllerLogger recordingControllerLogger,
            MediaProjectionMetricsLogger mediaProjectionMetricsLogger,
            ScreenCaptureDisabledDialogDelegate screenCaptureDisabledDialogDelegate,
            ScreenRecordPermissionDialogDelegate.Factory
                    screenRecordPermissionDialogDelegateFactory) {
        mMainExecutor = mainExecutor;
        mDevicePolicyResolver = devicePolicyResolver;
        mBroadcastDispatcher = broadcastDispatcher;
        mUserTracker = userTracker;
        mRecordingControllerLogger = recordingControllerLogger;
        mMediaProjectionMetricsLogger = mediaProjectionMetricsLogger;
        mScreenCaptureDisabledDialogDelegate = screenCaptureDisabledDialogDelegate;
        mScreenRecordPermissionDialogDelegateFactory = screenRecordPermissionDialogDelegateFactory;

        BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setInteractive(true);
        mInteractiveBroadcastOption = options.toBundle();
    }

    /**
     * MediaProjection host is SystemUI for the screen recorder, so return 'my user handle'
     */
    private UserHandle getHostUserHandle() {
        return UserHandle.of(UserHandle.myUserId());
    }

    /**
     * MediaProjection host is SystemUI for the screen recorder, so return 'my process uid'
     */
    private int getHostUid() {
        return Process.myUid();
    }

    /** Create a dialog to show screen recording options to the user.
     *  If screen capturing is currently not allowed it will return a dialog
     *  that warns users about it. */
    public Dialog createScreenRecordDialog(@Nullable Runnable onStartRecordingClicked) {
        if (mDevicePolicyResolver.get()
                        .isScreenCaptureCompletelyDisabled(getHostUserHandle())) {
            return mScreenCaptureDisabledDialogDelegate.createSysUIDialog();
        }

        mMediaProjectionMetricsLogger.notifyProjectionInitiated(
                getHostUid(), SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER);

        return mScreenRecordPermissionDialogDelegateFactory
                .create(this, getHostUserHandle(), getHostUid(), onStartRecordingClicked)
                .createDialog();
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
                    mRecordingControllerLogger.logSentStartIntent();
                } catch (PendingIntent.CanceledException e) {
                    mRecordingControllerLogger.logPendingIntentCancelled(e);
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
            mRecordingControllerLogger.logCountdownCancelled();
            mCountDownTimer.cancel();
        } else {
            mRecordingControllerLogger.logCountdownCancelErrorNoTimer();
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
     * Stop the recording and sets the stop reason to be used by the RecordingService
     * @param stopReason the method of the recording stopped (i.e. QS tile, status bar chip, etc.)
     */
    public void stopRecording(@StopReason int stopReason) {
        mStopReason = stopReason;
        try {
            if (mStopIntent != null) {
                mRecordingControllerLogger.logRecordingStopped();
                mStopIntent.send(mInteractiveBroadcastOption);
            } else {
                mRecordingControllerLogger.logRecordingStopErrorNoStopIntent();
            }
            updateState(false);
        } catch (PendingIntent.CanceledException e) {
            mRecordingControllerLogger.logRecordingStopError(e);
        }
    }

    /**
     * Update the current status
     * @param isRecording
     */
    public synchronized void updateState(boolean isRecording) {
        mRecordingControllerLogger.logStateUpdated(isRecording);
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

    public @StopReason int getStopReason() {
        return mStopReason;
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
