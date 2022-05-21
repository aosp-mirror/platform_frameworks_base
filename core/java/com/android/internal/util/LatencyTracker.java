/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.internal.util;

import static android.os.Trace.TRACE_TAG_APP;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.EventLogTags;
import com.android.internal.os.BackgroundThread;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Class to track various latencies in SystemUI. It then writes the latency to statsd and also
 * outputs it to logcat so these latencies can be captured by tests and then used for dashboards.
 * <p>
 * This is currently only in Keyguard so it can be shared between SystemUI and Keyguard, but
 * eventually we'd want to merge these two packages together so Keyguard can use common classes
 * that are shared with SystemUI.
 */
public class LatencyTracker {
    private static final String TAG = "LatencyTracker";
    public static final String SETTINGS_ENABLED_KEY = "enabled";
    private static final String SETTINGS_SAMPLING_INTERVAL_KEY = "sampling_interval";
    private static final boolean DEBUG = false;
    /** Default to being enabled on debug builds. */
    private static final boolean DEFAULT_ENABLED = Build.IS_DEBUGGABLE;
    /** Default to collecting data for 1/5 of all actions (randomly sampled). */
    private static final int DEFAULT_SAMPLING_INTERVAL = 5;

    /**
     * Time it takes until the first frame of the notification panel to be displayed while expanding
     */
    public static final int ACTION_EXPAND_PANEL = 0;

    /**
     * Time it takes until the first frame of recents is drawn after invoking it with the button.
     */
    public static final int ACTION_TOGGLE_RECENTS = 1;

    /**
     * Time between we get a fingerprint acquired signal until we start with the unlock animation
     */
    public static final int ACTION_FINGERPRINT_WAKE_AND_UNLOCK = 2;

    /**
     * Time it takes to check PIN/Pattern/Password.
     */
    public static final int ACTION_CHECK_CREDENTIAL = 3;

    /**
     * Time it takes to check fully PIN/Pattern/Password, i.e. that's the time spent including the
     * actions to unlock a user.
     */
    public static final int ACTION_CHECK_CREDENTIAL_UNLOCKED = 4;

    /**
     * Time it takes to turn on the screen.
     */
    public static final int ACTION_TURN_ON_SCREEN = 5;

    /**
     * Time it takes to rotate the screen.
     */
    public static final int ACTION_ROTATE_SCREEN = 6;

    /*
     * Time between we get a face acquired signal until we start with the unlock animation
     */
    public static final int ACTION_FACE_WAKE_AND_UNLOCK = 7;

    /**
     * Time between the swipe-up gesture and window drawn of recents activity.
     */
    public static final int ACTION_START_RECENTS_ANIMATION = 8;

    /**
     * Time it takes the sensor to detect rotation.
     */
    public static final int ACTION_ROTATE_SCREEN_SENSOR = 9;

    /**
     * Time it takes to for the camera based algorithm to rotate the screen.
     */
    public static final int ACTION_ROTATE_SCREEN_CAMERA_CHECK = 10;

    /**
     * Time it takes to start unlock animation .
     */
    public static final int ACTION_LOCKSCREEN_UNLOCK = 11;

    /**
     * Time it takes to switch users.
     */
    public static final int ACTION_USER_SWITCH = 12;

    /**
     * Time it takes to turn on the inner screen for a foldable device.
     */
    public static final int ACTION_SWITCH_DISPLAY_UNFOLD = 13;

    /**
     * Time it takes for a UDFPS sensor to appear ready after it is touched.
     */
    public static final int ACTION_UDFPS_ILLUMINATE = 14;

    /**
     * Time it takes for the gesture back affordance arrow to show up.
     */
    public static final int ACTION_SHOW_BACK_ARROW = 15;

    /**
     * Time it takes for loading share sheet.
     */
    public static final int ACTION_LOAD_SHARE_SHEET = 16;

    private static final int[] ACTIONS_ALL = {
        ACTION_EXPAND_PANEL,
        ACTION_TOGGLE_RECENTS,
        ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
        ACTION_CHECK_CREDENTIAL,
        ACTION_CHECK_CREDENTIAL_UNLOCKED,
        ACTION_TURN_ON_SCREEN,
        ACTION_ROTATE_SCREEN,
        ACTION_FACE_WAKE_AND_UNLOCK,
        ACTION_START_RECENTS_ANIMATION,
        ACTION_ROTATE_SCREEN_SENSOR,
        ACTION_ROTATE_SCREEN_CAMERA_CHECK,
        ACTION_LOCKSCREEN_UNLOCK,
        ACTION_USER_SWITCH,
        ACTION_SWITCH_DISPLAY_UNFOLD,
        ACTION_UDFPS_ILLUMINATE,
        ACTION_SHOW_BACK_ARROW,
        ACTION_LOAD_SHARE_SHEET,
    };

    /** @hide */
    @IntDef({
        ACTION_EXPAND_PANEL,
        ACTION_TOGGLE_RECENTS,
        ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
        ACTION_CHECK_CREDENTIAL,
        ACTION_CHECK_CREDENTIAL_UNLOCKED,
        ACTION_TURN_ON_SCREEN,
        ACTION_ROTATE_SCREEN,
        ACTION_FACE_WAKE_AND_UNLOCK,
        ACTION_START_RECENTS_ANIMATION,
        ACTION_ROTATE_SCREEN_SENSOR,
        ACTION_ROTATE_SCREEN_CAMERA_CHECK,
        ACTION_LOCKSCREEN_UNLOCK,
        ACTION_USER_SWITCH,
        ACTION_SWITCH_DISPLAY_UNFOLD,
        ACTION_UDFPS_ILLUMINATE,
        ACTION_SHOW_BACK_ARROW,
        ACTION_LOAD_SHARE_SHEET,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    private static final int[] STATSD_ACTION = new int[]{
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_EXPAND_PANEL,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_TOGGLE_RECENTS,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL_UNLOCKED,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_TURN_ON_SCREEN,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FACE_WAKE_AND_UNLOCK,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_START_RECENTS_ANIMATION,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_SENSOR,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN_CAMERA_CHECK,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOCKSCREEN_UNLOCK,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_USER_SWITCH,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SWITCH_DISPLAY_UNFOLD,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_UDFPS_ILLUMINATE,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_SHOW_BACK_ARROW,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_LOAD_SHARE_SHEET,
    };

    private static LatencyTracker sLatencyTracker;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final SparseArray<Session> mSessions = new SparseArray<>();
    @GuardedBy("mLock")
    private final int[] mTraceThresholdPerAction = new int[ACTIONS_ALL.length];
    @GuardedBy("mLock")
    private int mSamplingInterval;
    @GuardedBy("mLock")
    private boolean mEnabled;

    public static LatencyTracker getInstance(Context context) {
        if (sLatencyTracker == null) {
            synchronized (LatencyTracker.class) {
                if (sLatencyTracker == null) {
                    sLatencyTracker = new LatencyTracker();
                }
            }
        }
        return sLatencyTracker;
    }

    private LatencyTracker() {
        mEnabled = DEFAULT_ENABLED;
        mSamplingInterval = DEFAULT_SAMPLING_INTERVAL;

        // Post initialization to the background in case we're running on the main thread.
        BackgroundThread.getHandler().post(() -> this.updateProperties(
                DeviceConfig.getProperties(DeviceConfig.NAMESPACE_LATENCY_TRACKER)));
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_LATENCY_TRACKER,
                BackgroundThread.getExecutor(), this::updateProperties);
    }

    private void updateProperties(DeviceConfig.Properties properties) {
        synchronized (mLock) {
            mSamplingInterval = properties.getInt(SETTINGS_SAMPLING_INTERVAL_KEY,
                    DEFAULT_SAMPLING_INTERVAL);
            mEnabled = properties.getBoolean(SETTINGS_ENABLED_KEY, DEFAULT_ENABLED);
            for (int action : ACTIONS_ALL) {
                mTraceThresholdPerAction[action] =
                    properties.getInt(getNameOfAction(STATSD_ACTION[action]), -1);
            }
        }
    }

    /**
     * A helper method to translate action type to name.
     *
     * @param atomsProtoAction the action type defined in AtomsProto.java
     * @return the name of the action
     */
    public static String getNameOfAction(int atomsProtoAction) {
        // Defined in AtomsProto.java
        switch (atomsProtoAction) {
            case 0:
                return "UNKNOWN";
            case 1:
                return "ACTION_EXPAND_PANEL";
            case 2:
                return "ACTION_TOGGLE_RECENTS";
            case 3:
                return "ACTION_FINGERPRINT_WAKE_AND_UNLOCK";
            case 4:
                return "ACTION_CHECK_CREDENTIAL";
            case 5:
                return "ACTION_CHECK_CREDENTIAL_UNLOCKED";
            case 6:
                return "ACTION_TURN_ON_SCREEN";
            case 7:
                return "ACTION_ROTATE_SCREEN";
            case 8:
                return "ACTION_FACE_WAKE_AND_UNLOCK";
            case 9:
                return "ACTION_START_RECENTS_ANIMATION";
            case 10:
                return "ACTION_ROTATE_SCREEN_CAMERA_CHECK";
            case 11:
                return "ACTION_ROTATE_SCREEN_SENSOR";
            case 12:
                return "ACTION_LOCKSCREEN_UNLOCK";
            case 13:
                return "ACTION_USER_SWITCH";
            case 14:
                return "ACTION_SWITCH_DISPLAY_UNFOLD";
            case 15:
                return "ACTION_UDFPS_ILLUMINATE";
            case 16:
                return "ACTION_SHOW_BACK_ARROW";
            case 17:
                return "ACTION_LOAD_SHARE_SHEET";
            default:
                throw new IllegalArgumentException("Invalid action");
        }
    }

    private static String getTraceNameOfAction(@Action int action, String tag) {
        if (TextUtils.isEmpty(tag)) {
            return "L<" + getNameOfAction(STATSD_ACTION[action]) + ">";
        } else {
            return "L<" + getNameOfAction(STATSD_ACTION[action]) + "::" + tag + ">";
        }
    }

    private static String getTraceTriggerNameForAction(@Action int action) {
        return "com.android.telemetry.latency-tracker-" + getNameOfAction(STATSD_ACTION[action]);
    }

    public static boolean isEnabled(Context ctx) {
        return getInstance(ctx).isEnabled();
    }

    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    /**
     * Notifies that an action is starting. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to start. One of the ACTION_* values.
     */
    public void onActionStart(@Action int action) {
        onActionStart(action, null);
    }

    /**
     * Notifies that an action is starting. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to start. One of the ACTION_* values.
     * @param tag The brief description of the action.
     */
    public void onActionStart(@Action int action, String tag) {
        synchronized (mLock) {
            if (!isEnabled()) {
                return;
            }
            // skip if the action is already instrumenting.
            if (mSessions.get(action) != null) {
                return;
            }
            Session session = new Session(action, tag);
            session.begin(() -> onActionCancel(action));
            mSessions.put(action, session);

            if (DEBUG) {
                Log.d(TAG, "onActionStart: " + session.name() + ", start=" + session.mStartRtc);
            }
        }
    }

    /**
     * Notifies that an action has ended. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to end. One of the ACTION_* values.
     */
    public void onActionEnd(@Action int action) {
        synchronized (mLock) {
            if (!isEnabled()) {
                return;
            }
            Session session = mSessions.get(action);
            if (session == null) {
                return;
            }
            session.end();
            mSessions.delete(action);
            logAction(action, session.duration());

            if (DEBUG) {
                Log.d(TAG, "onActionEnd:" + session.name() + ", duration=" + session.duration());
            }
        }
    }

    /**
     * Notifies that an action has canceled. <s>This needs to be called from the main thread.</s>
     *
     * @param action The action to cancel. One of the ACTION_* values.
     * @hide
     */
    public void onActionCancel(@Action int action) {
        synchronized (mLock) {
            Session session = mSessions.get(action);
            if (session == null) {
                return;
            }
            session.cancel();
            mSessions.delete(action);

            if (DEBUG) {
                Log.d(TAG, "onActionCancel: " + session.name());
            }
        }
    }

    /**
     * Logs an action that has started and ended. This needs to be called from the main thread.
     *
     * @param action   The action to end. One of the ACTION_* values.
     * @param duration The duration of the action in ms.
     */
    public void logAction(@Action int action, int duration) {
        boolean shouldSample;
        int traceThreshold;
        synchronized (mLock) {
            shouldSample = ThreadLocalRandom.current().nextInt() % mSamplingInterval == 0;
            traceThreshold = mTraceThresholdPerAction[action];
        }

        if (traceThreshold > 0 && duration >= traceThreshold) {
            PerfettoTrigger.trigger(getTraceTriggerNameForAction(action));
        }

        logActionDeprecated(action, duration, shouldSample);
    }

    /**
     * Logs an action that has started and ended. This needs to be called from the main thread.
     *
     * @param action The action to end. One of the ACTION_* values.
     * @param duration The duration of the action in ms.
     * @param writeToStatsLog Whether to write the measured latency to FrameworkStatsLog.
     */
    public static void logActionDeprecated(
            @Action int action, int duration, boolean writeToStatsLog) {
        Log.i(TAG, getNameOfAction(STATSD_ACTION[action]) + " latency=" + duration);
        EventLog.writeEvent(EventLogTags.SYSUI_LATENCY, action, duration);

        if (writeToStatsLog) {
            FrameworkStatsLog.write(
                    FrameworkStatsLog.UI_ACTION_LATENCY_REPORTED, STATSD_ACTION[action], duration);
        }
    }

    static class Session {
        @Action
        private final int mAction;
        private final String mTag;
        private final String mName;
        private Runnable mTimeoutRunnable;
        private long mStartRtc = -1;
        private long mEndRtc = -1;

        Session(@Action int action, @Nullable String tag) {
            mAction = action;
            mTag = tag;
            mName = TextUtils.isEmpty(mTag)
                    ? getNameOfAction(STATSD_ACTION[mAction])
                    : getNameOfAction(STATSD_ACTION[mAction]) + "::" + mTag;
        }

        String name() {
            return mName;
        }

        String traceName() {
            return getTraceNameOfAction(mAction, mTag);
        }

        void begin(@NonNull Runnable timeoutAction) {
            mStartRtc = SystemClock.elapsedRealtime();
            Trace.asyncTraceBegin(TRACE_TAG_APP, traceName(), 0);

            // start counting timeout.
            mTimeoutRunnable = timeoutAction;
            BackgroundThread.getHandler()
                    .postDelayed(mTimeoutRunnable, TimeUnit.SECONDS.toMillis(15));
        }

        void end() {
            mEndRtc = SystemClock.elapsedRealtime();
            Trace.asyncTraceEnd(TRACE_TAG_APP, traceName(), 0);
            BackgroundThread.getHandler().removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }

        void cancel() {
            Trace.asyncTraceEnd(TRACE_TAG_APP, traceName(), 0);
            BackgroundThread.getHandler().removeCallbacks(mTimeoutRunnable);
            mTimeoutRunnable = null;
        }

        int duration() {
            return (int) (mEndRtc - mStartRtc);
        }
    }
}
