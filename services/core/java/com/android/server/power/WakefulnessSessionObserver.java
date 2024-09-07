/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.power;

import static android.os.PowerManager.USER_ACTIVITY_EVENT_OTHER;
import static android.os.PowerManagerInternal.isInteractive;

import static com.android.server.power.PowerManagerService.DEFAULT_SCREEN_OFF_TIMEOUT;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_NON_INTERACTIVE;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_SCREEN_LOCK;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_UNKNOWN;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_ACCESSIBILITY;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_ATTENTION;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_BUTTON;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_OTHER;
import static com.android.server.power.ScreenTimeoutOverridePolicy.RELEASE_REASON_USER_ACTIVITY_TOUCH;

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.SynchronousUserSwitchObserver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.IndentingPrintWriter;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Observe the wakefulness session of the device, tracking the reason and the
 * last user activity when the interactive state is off.
 */
public class WakefulnessSessionObserver {
    private static final String TAG = "WakefulnessSessionObserver";

    private static final int OFF_REASON_UNKNOWN = FrameworkStatsLog
            .SCREEN_INTERACTIVE_SESSION_REPORTED__INTERACTIVE_STATE_OFF_REASON__UNKNOWN;
    private static final int OFF_REASON_TIMEOUT = FrameworkStatsLog
            .SCREEN_INTERACTIVE_SESSION_REPORTED__INTERACTIVE_STATE_OFF_REASON__TIMEOUT;
    @VisibleForTesting
    protected static final int OFF_REASON_POWER_BUTTON = FrameworkStatsLog
            .SCREEN_INTERACTIVE_SESSION_REPORTED__INTERACTIVE_STATE_OFF_REASON__POWER_BUTTON;

    /**
     * Interactive off reason
     * {@link android.os.statsd.power.ScreenInteractiveSessionReported.InteractiveStateOffReason}.
     */
    @IntDef(prefix = {"OFF_REASON_"}, value = {
            OFF_REASON_UNKNOWN,
            OFF_REASON_TIMEOUT,
            OFF_REASON_POWER_BUTTON
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface OffReason {}

    private static final int OVERRIDE_OUTCOME_UNKNOWN = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__UNKNOWN;
    @VisibleForTesting
    protected static final int OVERRIDE_OUTCOME_TIMEOUT_SUCCESS = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__TIMEOUT_SUCCESS;
    @VisibleForTesting
    protected static final int OVERRIDE_OUTCOME_TIMEOUT_USER_INITIATED_REVERT = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__TIMEOUT_USER_INITIATED_REVERT;
    private static final int OVERRIDE_OUTCOME_CANCEL_CLIENT_API_CALL = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__CANCEL_CLIENT_API_CALL;
    @VisibleForTesting
    protected static final int OVERRIDE_OUTCOME_CANCEL_USER_INTERACTION = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__CANCEL_USER_INTERACTION;
    @VisibleForTesting
    protected static final int OVERRIDE_OUTCOME_CANCEL_POWER_BUTTON = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__CANCEL_POWER_BUTTON;
    private static final int OVERRIDE_OUTCOME_CANCEL_CLIENT_DISCONNECT = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__CANCEL_CLIENT_DISCONNECTED;
    private static final int OVERRIDE_OUTCOME_CANCEL_OTHER = FrameworkStatsLog
            .SCREEN_TIMEOUT_OVERRIDE_REPORTED__OVERRIDE_OUTCOME__CANCEL_OTHER;

    /**
     * Override Outcome
     * {@link android.os.statsd.power.ScreenTimeoutOverrideReported.OverrideOutcome}.
     */
    @IntDef(prefix = {"OVERRIDE_OUTCOME_"}, value = {
            OVERRIDE_OUTCOME_UNKNOWN,
            OVERRIDE_OUTCOME_TIMEOUT_SUCCESS,
            OVERRIDE_OUTCOME_TIMEOUT_USER_INITIATED_REVERT,
            OVERRIDE_OUTCOME_CANCEL_CLIENT_API_CALL,
            OVERRIDE_OUTCOME_CANCEL_USER_INTERACTION,
            OVERRIDE_OUTCOME_CANCEL_POWER_BUTTON,
            OVERRIDE_OUTCOME_CANCEL_CLIENT_DISCONNECT,
            OVERRIDE_OUTCOME_CANCEL_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface OverrideOutcome {}

    private static final int DEFAULT_USER_ACTIVITY = USER_ACTIVITY_EVENT_OTHER;
    private static final long TIMEOUT_USER_INITIATED_REVERT_THRESHOLD_MILLIS = 5000L;
    private static final long SEND_OVERRIDE_TIMEOUT_LOG_THRESHOLD_MILLIS = 1000L;

    private Context mContext;
    private int mScreenOffTimeoutMs;
    private int mOverrideTimeoutMs = 0;
    @VisibleForTesting
    protected final SparseArray<WakefulnessSessionPowerGroup> mPowerGroups = new SparseArray<>();
    @VisibleForTesting
    protected WakefulnessSessionFrameworkStatsLogger mWakefulnessSessionFrameworkStatsLogger;
    private final Clock mClock;
    private final Object mLock = new Object();

    public WakefulnessSessionObserver(Context context, Injector injector) {
        if (injector == null) {
            injector = new Injector();
        }

        mContext = context;
        mWakefulnessSessionFrameworkStatsLogger = injector
                .getWakefulnessSessionFrameworkStatsLogger();
        mClock = injector.getClock();
        updateSettingScreenOffTimeout(context);

        try {
            final UserSwitchObserver observer = new UserSwitchObserver();
            ActivityManager.getService().registerUserSwitchObserver(observer, TAG);
        } catch (RemoteException e) {
            // Shouldn't happen since in-process.
        }

        mOverrideTimeoutMs = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenTimeoutOverride);

        mContext.getContentResolver()
                .registerContentObserver(
                        Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT),
                        false,
                        new ContentObserver(new Handler(mContext.getMainLooper())) {
                            @Override
                            public void onChange(boolean selfChange) {
                                updateSettingScreenOffTimeout(mContext);
                            }
                        },
                        UserHandle.USER_ALL);

        mPowerGroups.append(
                Display.DEFAULT_DISPLAY_GROUP,
                new WakefulnessSessionPowerGroup(Display.DEFAULT_DISPLAY_GROUP));
    }

    /**
     * Track the user activity event.
     *
     * @param eventTime Activity time, in uptime millis.
     * @param powerGroupId Power Group Id for this user activity
     * @param event Activity type as defined in {@link PowerManager}. {@link
     *     android.hardware.display.DisplayManagerInternal.DisplayPowerRequest}
     */
    public void notifyUserActivity(
            long eventTime, int powerGroupId, @PowerManager.UserActivityEvent int event) {
        if (!mPowerGroups.contains(powerGroupId)) {
            mPowerGroups.append(powerGroupId, new WakefulnessSessionPowerGroup(powerGroupId));
        }
        mPowerGroups.get(powerGroupId).notifyUserActivity(eventTime, event);
    }

    /**
     * Track the system wakefulness
     *
     * @param powerGroupId Power Group Id for this wakefulness changes
     * @param wakefulness Wakefulness as defined in {@link PowerManagerInternal}
     * @param changeReason Reason of the go to sleep in
     * {@link PowerManager.GoToSleepReason} or {@link PowerManager.WakeReason}
     * @param eventTime timestamp of the wakefulness changes
     */
    public void onWakefulnessChangeStarted(int powerGroupId, int wakefulness, int changeReason,
            long eventTime) {
        if (!mPowerGroups.contains(powerGroupId)) {
            mPowerGroups.append(powerGroupId, new WakefulnessSessionPowerGroup(powerGroupId));
        }
        mPowerGroups.get(powerGroupId).onWakefulnessChangeStarted(wakefulness, changeReason,
                eventTime);
    }

    /**
     * Track the acquired wakelocks
     *
     * @param flags wakelocks to be acquired {@link PowerManager}
     */
    public void onWakeLockAcquired(int flags) {
        int maskedFlag = flags & PowerManager.WAKE_LOCK_LEVEL_MASK;
        if (maskedFlag == PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK) {
            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                mPowerGroups.valueAt(idx).acquireTimeoutOverrideWakeLock();
            }
        }
    }

    /**
     * Track the released wakelocks
     *
     * @param flags wakelocks to be released {@link PowerManager}
     * @param releaseReason the reason to release wakelock
     * {@link ScreenTimeoutOverridePolicy.ReleaseReason}
     */
    public void onWakeLockReleased(int flags, int releaseReason) {
        int maskedFlag = flags & PowerManager.WAKE_LOCK_LEVEL_MASK;
        if (maskedFlag == PowerManager.SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK) {
            for (int idx = 0; idx < mPowerGroups.size(); idx++) {
                mPowerGroups.valueAt(idx).releaseTimeoutOverrideWakeLock(releaseReason);
            }
        }
    }

    /**
     * Remove the inactive power group
     *
     * @param powerGroupId Power Group Id that should be removed
     */
    public void removePowerGroup(int powerGroupId) {
        if (mPowerGroups.contains((powerGroupId))) {
            mPowerGroups.delete(powerGroupId);
        }
    }

    void dump(PrintWriter writer) {
        writer.println();
        writer.println("Wakefulness Session Observer:");
        writer.println("default timeout: " + mScreenOffTimeoutMs);
        writer.println("override timeout: " + mOverrideTimeoutMs);
        IndentingPrintWriter indentingPrintWriter = new IndentingPrintWriter(writer);
        indentingPrintWriter.increaseIndent();
        for (int idx = 0; idx < mPowerGroups.size(); idx++) {
            mPowerGroups.valueAt(idx).dump(indentingPrintWriter);
        }
        writer.println();
    }

    private void updateSettingScreenOffTimeout(Context context) {
        synchronized (mLock) {
            mScreenOffTimeoutMs = Settings.System.getIntForUser(
                    context.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT,
                    DEFAULT_SCREEN_OFF_TIMEOUT,
                    UserHandle.USER_CURRENT);
        }
    }

    private int getScreenOffTimeout() {
        synchronized (mLock) {
            return mScreenOffTimeoutMs;
        }
    }

    /** Screen Session by each power group */
    @VisibleForTesting
    protected class WakefulnessSessionPowerGroup {
        private static final long TIMEOUT_OFF_RESET_TIMESTAMP = -1;

        private int mPowerGroupId;
        private int mCurrentWakefulness;
        private boolean mIsInteractive = false;
        // state on start timestamp: will be used in state off to calculate the duration of state on
        private long mInteractiveStateOnStartTimestamp;
        @VisibleForTesting
        protected long mCurrentUserActivityTimestamp;
        @VisibleForTesting
        protected @PowerManager.UserActivityEvent int mCurrentUserActivityEvent;
        @VisibleForTesting
        protected long mPrevUserActivityTimestamp;
        @VisibleForTesting
        protected @PowerManager.UserActivityEvent int mPrevUserActivityEvent;
        // to track the Override Timeout is set (that is, on SCREEN_TIMEOUT_OVERRIDE_WAKE_LOCK)
        private int mTimeoutOverrideWakeLockCounter = 0;
        // The timestamp when Override Timeout is set to false
        private @ScreenTimeoutOverridePolicy.ReleaseReason int mTimeoutOverrideReleaseReason;
        // The timestamp when state off by timeout occurs
        // will set TIMEOUT_OFF_RESET_TIMESTAMP if state on or state off by power button
        private long mTimeoutOffTimestamp;
        // The timestamp for the latest logTimeoutOverrideEvent calling
        private long mSendOverrideTimeoutLogTimestamp;

        public WakefulnessSessionPowerGroup(int powerGroupId) {
            mCurrentUserActivityEvent = DEFAULT_USER_ACTIVITY;
            mCurrentUserActivityTimestamp = -1;
            mPrevUserActivityEvent = DEFAULT_USER_ACTIVITY;
            mPrevUserActivityTimestamp = -1;
            mPowerGroupId = powerGroupId;
        }

        public void notifyUserActivity(long eventTime, @PowerManager.UserActivityEvent int event) {
            // only track when user activity changes
            if (event == mCurrentUserActivityEvent) {
                return;
            }
            mPrevUserActivityEvent = mCurrentUserActivityEvent;
            mCurrentUserActivityEvent = event;
            mPrevUserActivityTimestamp = mCurrentUserActivityTimestamp;
            mCurrentUserActivityTimestamp = eventTime;
        }

        public void onWakefulnessChangeStarted(int wakefulness, int changeReason, long eventTime) {
            mCurrentWakefulness = wakefulness;
            if (mIsInteractive == isInteractive(wakefulness)) {
                return;
            }

            mIsInteractive = isInteractive(wakefulness);
            if (mIsInteractive) {
                mInteractiveStateOnStartTimestamp = eventTime;

                // Log the outcome of screen timeout override (USER INITIATED REVERT),
                // when user initiates to revert the screen off state in a short period.
                if (mTimeoutOffTimestamp != TIMEOUT_OFF_RESET_TIMESTAMP) {
                    long offToOnDurationMs = eventTime - mTimeoutOffTimestamp;
                    if (offToOnDurationMs < TIMEOUT_USER_INITIATED_REVERT_THRESHOLD_MILLIS) {
                        mWakefulnessSessionFrameworkStatsLogger.logTimeoutOverrideEvent(
                                mPowerGroupId,
                                OVERRIDE_OUTCOME_TIMEOUT_USER_INITIATED_REVERT,
                                mOverrideTimeoutMs,
                                getScreenOffTimeout());
                        mSendOverrideTimeoutLogTimestamp = eventTime;
                    }
                    mTimeoutOffTimestamp = TIMEOUT_OFF_RESET_TIMESTAMP;
                }
            } else {
                int lastUserActivity = mCurrentUserActivityEvent;
                long lastUserActivityDurationMs = eventTime - mCurrentUserActivityTimestamp;
                @OffReason int interactiveStateOffReason = OFF_REASON_UNKNOWN;
                int reducedInteractiveStateOnDurationMs = 0;

                if (changeReason == PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON) {
                    interactiveStateOffReason = OFF_REASON_POWER_BUTTON;

                    // Power Off will be triggered by USER_ACTIVITY_EVENT_BUTTON
                    // The metric wants to record the previous activity before EVENT_BUTTON
                    lastUserActivity = mPrevUserActivityEvent;
                    lastUserActivityDurationMs = eventTime - mPrevUserActivityTimestamp;

                    if (isInOverrideTimeout()
                            || mTimeoutOverrideReleaseReason == RELEASE_REASON_USER_ACTIVITY_BUTTON
                    ) {
                        mWakefulnessSessionFrameworkStatsLogger.logTimeoutOverrideEvent(
                                mPowerGroupId,
                                OVERRIDE_OUTCOME_CANCEL_POWER_BUTTON,
                                mOverrideTimeoutMs,
                                getScreenOffTimeout());
                        mSendOverrideTimeoutLogTimestamp = eventTime;
                        mTimeoutOverrideReleaseReason = RELEASE_REASON_UNKNOWN; // reset the reason
                    }
                } else if (changeReason == PowerManager.GO_TO_SLEEP_REASON_TIMEOUT) {
                    // Interactive Off reason is timeout
                    interactiveStateOffReason = OFF_REASON_TIMEOUT;

                    lastUserActivity = mCurrentUserActivityEvent;
                    lastUserActivityDurationMs = eventTime - mCurrentUserActivityTimestamp;

                    // Log the outcome of screen timeout override when the early screen
                    // timeout has been done successfully.
                    if (isInOverrideTimeout()) {
                        reducedInteractiveStateOnDurationMs =
                                getScreenOffTimeout() - mOverrideTimeoutMs;

                        mWakefulnessSessionFrameworkStatsLogger.logTimeoutOverrideEvent(
                                mPowerGroupId,
                                OVERRIDE_OUTCOME_TIMEOUT_SUCCESS,
                                mOverrideTimeoutMs,
                                getScreenOffTimeout());
                        mSendOverrideTimeoutLogTimestamp = eventTime;

                        // Record a timestamp to track if the user initiates to revert from off
                        // state instantly
                        mTimeoutOffTimestamp = eventTime;
                    }
                }

                long interactiveStateOnDurationMs =
                        eventTime - mInteractiveStateOnStartTimestamp;
                mWakefulnessSessionFrameworkStatsLogger.logSessionEvent(
                        mPowerGroupId,
                        interactiveStateOffReason,
                        interactiveStateOnDurationMs,
                        lastUserActivity,
                        lastUserActivityDurationMs,
                        reducedInteractiveStateOnDurationMs);
            }
        }

        public void acquireTimeoutOverrideWakeLock() {
            synchronized (mLock) {
                mTimeoutOverrideWakeLockCounter++;
            }
        }

        public void releaseTimeoutOverrideWakeLock(
                @ScreenTimeoutOverridePolicy.ReleaseReason  int releaseReason) {
            synchronized (mLock) {
                mTimeoutOverrideWakeLockCounter--;
            }

            if (!isInOverrideTimeout()) {
                mTimeoutOverrideReleaseReason = releaseReason;
                long now = mClock.uptimeMillis();

                // Log the outcome of screen timeout override (USER INTERACTIVE or DISCONNECT),
                // when early screen timeout be canceled.
                // Note: Set the threshold to avoid sending this log repeatly after other outcomes.
                long sendOverrideTimeoutLogDuration = now - mSendOverrideTimeoutLogTimestamp;
                boolean sendOverrideTimeoutLogSoon = sendOverrideTimeoutLogDuration
                        < SEND_OVERRIDE_TIMEOUT_LOG_THRESHOLD_MILLIS;
                if (!sendOverrideTimeoutLogSoon) {
                    @OverrideOutcome int outcome = OVERRIDE_OUTCOME_UNKNOWN;
                    switch (releaseReason) {
                        case RELEASE_REASON_USER_ACTIVITY_ATTENTION:
                        case RELEASE_REASON_USER_ACTIVITY_OTHER:
                        case RELEASE_REASON_USER_ACTIVITY_BUTTON:
                        case RELEASE_REASON_USER_ACTIVITY_TOUCH:
                        case RELEASE_REASON_USER_ACTIVITY_ACCESSIBILITY:
                            outcome = OVERRIDE_OUTCOME_CANCEL_USER_INTERACTION;
                            break;
                        case RELEASE_REASON_SCREEN_LOCK:
                        case RELEASE_REASON_NON_INTERACTIVE:
                            outcome = OVERRIDE_OUTCOME_CANCEL_CLIENT_DISCONNECT;
                            break;
                        default:
                            outcome = OVERRIDE_OUTCOME_UNKNOWN;
                    }
                    mWakefulnessSessionFrameworkStatsLogger.logTimeoutOverrideEvent(
                            mPowerGroupId,
                            outcome,
                            mOverrideTimeoutMs,
                            getScreenOffTimeout());
                }
            }
        }

        @VisibleForTesting
        protected boolean isInOverrideTimeout() {
            synchronized (mLock) {
                return (mTimeoutOverrideWakeLockCounter > 0);
            }
        }

        void dump(IndentingPrintWriter writer) {
            final long now = mClock.uptimeMillis();

            writer.println("Wakefulness Session Power Group powerGroupId: " + mPowerGroupId);
            writer.increaseIndent();
            writer.println("current wakefulness: " + mCurrentWakefulness);
            writer.println("current user activity event: " + mCurrentUserActivityEvent);
            final long currentUserActivityDurationMs = now - mCurrentUserActivityTimestamp;
            writer.println("current user activity duration: " + currentUserActivityDurationMs);
            writer.println("previous user activity event: " + mPrevUserActivityEvent);
            final long prevUserActivityDurationMs = now - mPrevUserActivityTimestamp;
            writer.println("previous user activity duration: " + prevUserActivityDurationMs);
            writer.println("is in override timeout: " + isInOverrideTimeout());
            writer.decreaseIndent();
        }
    }

    /** Log screen session atoms */
    protected static class WakefulnessSessionFrameworkStatsLogger {
        public void logSessionEvent(
                int powerGroupId,
                @OffReason int interactiveStateOffReason,
                long interactiveStateOnDurationMs,
                @PowerManager.UserActivityEvent int userActivityEvent,
                long lastUserActivityEventDurationMs,
                int reducedInteractiveStateOnDurationMs) {
            int logUserActivityEvent = convertToLogUserActivityEvent(userActivityEvent);
            FrameworkStatsLog.write(
                    FrameworkStatsLog.SCREEN_INTERACTIVE_SESSION_REPORTED,
                    powerGroupId,
                    interactiveStateOffReason,
                    interactiveStateOnDurationMs,
                    logUserActivityEvent,
                    lastUserActivityEventDurationMs,
                    (long) reducedInteractiveStateOnDurationMs);
        }

        public void logTimeoutOverrideEvent(
                int powerGroupId,
                @OverrideOutcome int overrideOutcome,
                int overrideTimeoutMs,
                int defaultTimeoutMs) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.SCREEN_TIMEOUT_OVERRIDE_REPORTED,
                    powerGroupId,
                    overrideOutcome,
                    (long) overrideTimeoutMs,
                    (long) defaultTimeoutMs);
        }

        private static final int USER_ACTIVITY_OTHER = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__OTHER;

        private static final int USER_ACTIVITY_BUTTON = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__BUTTON;

        private static final int USER_ACTIVITY_TOUCH = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__TOUCH;

        private static final int USER_ACTIVITY_ACCESSIBILITY = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__ACCESSIBILITY;
        private static final int USER_ACTIVITY_ATTENTION = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__ATTENTION;
        private static final int USER_ACTIVITY_FACE_DOWN = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__FACE_DOWN;

        private static final int USER_ACTIVITY_DEVICE_STATE = FrameworkStatsLog
                .SCREEN_INTERACTIVE_SESSION_REPORTED__LAST_USER_ACTIVITY_EVENT__DEVICE_STATE;

        /**
         * User Activity Event
         * {@link android.os.statsd.power.ScreenInteractiveSessionReported.UserActivityEvent}.
         */
        @IntDef(prefix = {"USER_ACTIVITY_"}, value = {
                USER_ACTIVITY_OTHER,
                USER_ACTIVITY_BUTTON,
                USER_ACTIVITY_TOUCH,
                USER_ACTIVITY_ACCESSIBILITY,
                USER_ACTIVITY_ATTENTION,
                USER_ACTIVITY_FACE_DOWN,
                USER_ACTIVITY_DEVICE_STATE,
        })
        @Retention(RetentionPolicy.SOURCE)
        private @interface UserActivityEvent {}

        private @UserActivityEvent int convertToLogUserActivityEvent(
                @PowerManager.UserActivityEvent int userActivity) {
            switch (userActivity) {
                case PowerManager.USER_ACTIVITY_EVENT_OTHER:
                    return USER_ACTIVITY_OTHER;
                case PowerManager.USER_ACTIVITY_EVENT_BUTTON:
                    return USER_ACTIVITY_BUTTON;
                case PowerManager.USER_ACTIVITY_EVENT_TOUCH:
                    return USER_ACTIVITY_TOUCH;
                case PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY:
                    return USER_ACTIVITY_ACCESSIBILITY;
                case PowerManager.USER_ACTIVITY_EVENT_ATTENTION:
                    return USER_ACTIVITY_ATTENTION;
                case PowerManager.USER_ACTIVITY_EVENT_FACE_DOWN:
                    return USER_ACTIVITY_FACE_DOWN;
                case PowerManager.USER_ACTIVITY_EVENT_DEVICE_STATE:
                    return USER_ACTIVITY_DEVICE_STATE;
            }
            return USER_ACTIVITY_OTHER;
        }
    }

    /** To observe and do actions if users switch */
    private final class UserSwitchObserver extends SynchronousUserSwitchObserver {
        @Override
        public void onUserSwitching(int newUserId) throws RemoteException {
            updateSettingScreenOffTimeout(mContext);
        }
    }

    @VisibleForTesting
    interface Clock {
        long uptimeMillis();
    }

    @VisibleForTesting
    static class Injector {
        WakefulnessSessionFrameworkStatsLogger getWakefulnessSessionFrameworkStatsLogger() {
            return new WakefulnessSessionFrameworkStatsLogger();
        }

        Clock getClock() {
            return SystemClock::uptimeMillis;
        }
    }
}
