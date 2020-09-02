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

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.os.Trace;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseLongArray;

import com.android.internal.logging.EventLogTags;

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

    private static final String[] NAMES = new String[] {
            "expand panel",
            "toggle recents",
            "fingerprint wake-and-unlock",
            "check credential",
            "check credential unlocked",
            "turn on screen",
            "rotate the screen",
            "face wake-and-unlock" };

    private static final int[] STATSD_ACTION = new int[] {
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_EXPAND_PANEL,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_TOGGLE_RECENTS,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FINGERPRINT_WAKE_AND_UNLOCK,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_CHECK_CREDENTIAL_UNLOCKED,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_TURN_ON_SCREEN,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_ROTATE_SCREEN,
            FrameworkStatsLog.UIACTION_LATENCY_REPORTED__ACTION__ACTION_FACE_WAKE_AND_UNLOCK,
    };

    private static LatencyTracker sLatencyTracker;

    private final SparseLongArray mStartRtc = new SparseLongArray();

    public static LatencyTracker getInstance(Context context) {
        if (sLatencyTracker == null) {
            sLatencyTracker = new LatencyTracker();
        }
        return sLatencyTracker;
    }

    public static boolean isEnabled(Context ctx) {
        return getInstance(ctx).isEnabled();
    }

    public boolean isEnabled() {
        return Build.IS_DEBUGGABLE;
    }

    /**
     * Notifies that an action is starting. This needs to be called from the main thread.
     *
     * @param action The action to start. One of the ACTION_* values.
     */
    public void onActionStart(int action) {
        if (!isEnabled()) {
            return;
        }
        Trace.asyncTraceBegin(Trace.TRACE_TAG_APP, NAMES[action], 0);
        mStartRtc.put(action, SystemClock.elapsedRealtime());
    }

    /**
     * Notifies that an action has ended. This needs to be called from the main thread.
     *
     * @param action The action to end. One of the ACTION_* values.
     */
    public void onActionEnd(int action) {
        if (!isEnabled()) {
            return;
        }
        long endRtc = SystemClock.elapsedRealtime();
        long startRtc = mStartRtc.get(action, -1);
        if (startRtc == -1) {
            return;
        }
        mStartRtc.delete(action);
        Trace.asyncTraceEnd(Trace.TRACE_TAG_APP, NAMES[action], 0);
        logAction(action, (int)(endRtc - startRtc));
    }

    /**
     * Logs an action that has started and ended. This needs to be called from the main thread.
     *
     * @param action The action to end. One of the ACTION_* values.
     * @param duration The duration of the action in ms.
     */
    public static void logAction(int action, int duration) {
        Log.i(TAG, "action=" + action + " latency=" + duration);
        EventLog.writeEvent(EventLogTags.SYSUI_LATENCY, action, duration);
        FrameworkStatsLog.write(
                FrameworkStatsLog.UI_ACTION_LATENCY_REPORTED, STATSD_ACTION[action], duration);
    }
}
