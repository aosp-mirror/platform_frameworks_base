/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.keyguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseLongArray;

import com.android.systemui.EventLogTags;

/**
 * Class to track various latencies in SystemUI. It then outputs the latency to logcat so these
 * latencies can be captured by tests and then used for dashboards.
 * <p>
 * This is currently only in Keyguard so it can be shared between SystemUI and Keyguard, but
 * eventually we'd want to merge these two packages together so Keyguard can use common classes
 * that are shared with SystemUI.
 */
public class LatencyTracker {

    private static final String ACTION_RELOAD_PROPERTY =
            "com.android.systemui.RELOAD_LATENCY_TRACKER_PROPERTY";

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

    private static final String[] NAMES = new String[] {
            "expand panel",
            "toggle recents",
            "fingerprint wake-and-unlock",
            "check credential",
            "check credential unlocked",
            "turn on screen" };

    private static LatencyTracker sLatencyTracker;

    private final SparseLongArray mStartRtc = new SparseLongArray();
    private boolean mEnabled;

    public static LatencyTracker getInstance(Context context) {
        if (sLatencyTracker == null) {
            sLatencyTracker = new LatencyTracker(context);
        }
        return sLatencyTracker;
    }

    private LatencyTracker(Context context) {
        context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reloadProperty();
            }
        }, new IntentFilter(ACTION_RELOAD_PROPERTY));
        reloadProperty();
    }

    private void reloadProperty() {
        mEnabled = SystemProperties.getBoolean("debug.systemui.latency_tracking", false);
    }

    public static boolean isEnabled(Context ctx) {
        return Build.IS_DEBUGGABLE && getInstance(ctx).mEnabled;
    }

    /**
     * Notifies that an action is starting. This needs to be called from the main thread.
     *
     * @param action The action to start. One of the ACTION_* values.
     */
    public void onActionStart(int action) {
        if (!mEnabled) {
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
        if (!mEnabled) {
            return;
        }
        long endRtc = SystemClock.elapsedRealtime();
        long startRtc = mStartRtc.get(action, -1);
        if (startRtc == -1) {
            return;
        }
        mStartRtc.delete(action);
        Trace.asyncTraceEnd(Trace.TRACE_TAG_APP, NAMES[action], 0);
        long duration = endRtc - startRtc;
        Log.i(TAG, "action=" + action + " latency=" + duration);
        EventLog.writeEvent(EventLogTags.SYSUI_LATENCY, action, (int) duration);
    }
}
