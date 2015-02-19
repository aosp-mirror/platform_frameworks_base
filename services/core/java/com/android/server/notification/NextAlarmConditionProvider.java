/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.notification;

import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.TimeUtils;

import com.android.internal.R;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;

/**
 * Built-in zen condition provider for alarm-clock-based conditions.
 *
 * <p>If the user's next alarm is within a lookahead threshold (config, default 12hrs), advertise
 * it as an exit condition for zen mode.
 *
 * <p>The next alarm is defined as {@link AlarmManager#getNextAlarmClock(int)}, which does not
 * survive a reboot.  Maintain the illusion of a consistent next alarm value by holding on to
 * a persisted condition until we receive the first value after reboot, or timeout with no value.
 */
public class NextAlarmConditionProvider extends ConditionProviderService {
    private static final String TAG = "NextAlarmConditions";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final long SECONDS = 1000;
    private static final long MINUTES = 60 * SECONDS;
    private static final long HOURS = 60 * MINUTES;

    private static final long BAD_CONDITION = -1;

    public static final ComponentName COMPONENT =
            new ComponentName("android", NextAlarmConditionProvider.class.getName());

    private final Context mContext = this;
    private final NextAlarmTracker mTracker;
    private final ArraySet<Uri> mSubscriptions = new ArraySet<Uri>();

    private boolean mConnected;
    private long mLookaheadThreshold;
    private boolean mRequesting;

    public NextAlarmConditionProvider(NextAlarmTracker tracker) {
        if (DEBUG) Slog.d(TAG, "new NextAlarmConditionProvider()");
        mTracker = tracker;
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    NextAlarmConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mLookaheadThreshold="); pw.print(mLookaheadThreshold);
        pw.print(" ("); TimeUtils.formatDuration(mLookaheadThreshold, pw); pw.println(")");
        pw.print("      mSubscriptions="); pw.println(mSubscriptions);
        pw.print("      mRequesting="); pw.println(mRequesting);
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mLookaheadThreshold = PropConfig.getInt(mContext, "nextalarm.condition.lookahead",
                R.integer.config_next_alarm_condition_lookahead_threshold_hrs) * HOURS;
        mConnected = true;
        mTracker.addCallback(mTrackerCallback);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DEBUG) Slog.d(TAG, "onDestroy");
        mTracker.removeCallback(mTrackerCallback);
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (DEBUG) Slog.d(TAG, "onRequestConditions relevance=" + relevance);
        if (!mConnected) return;
        mRequesting = (relevance & Condition.FLAG_RELEVANT_NOW) != 0;
        mTracker.evaluate();
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        if (tryParseNextAlarmCondition(conditionId) == BAD_CONDITION) {
            notifyCondition(conditionId, null, Condition.STATE_FALSE, "badCondition");
            return;
        }
        mSubscriptions.add(conditionId);
        mTracker.evaluate();
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        mSubscriptions.remove(conditionId);
    }

    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }

    private boolean isWithinLookaheadThreshold(AlarmClockInfo alarm) {
        if (alarm == null) return false;
        final long delta = NextAlarmTracker.getEarlyTriggerTime(alarm) - System.currentTimeMillis();
        return delta > 0 && (mLookaheadThreshold <= 0 || delta < mLookaheadThreshold);
    }

    private void notifyCondition(Uri id, AlarmClockInfo alarm, int state, String reason) {
        final String formattedAlarm = alarm == null ? "" : mTracker.formatAlarm(alarm);
        if (DEBUG) Slog.d(TAG, "notifyCondition " + Condition.stateToString(state)
                + " alarm=" + formattedAlarm + " reason=" + reason);
        notifyCondition(new Condition(id,
                mContext.getString(R.string.zen_mode_next_alarm_summary, formattedAlarm),
                mContext.getString(R.string.zen_mode_next_alarm_line_one),
                formattedAlarm, 0, state, Condition.FLAG_RELEVANT_NOW));
    }

    private Uri newConditionId(AlarmClockInfo nextAlarm) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(ZenModeConfig.SYSTEM_AUTHORITY)
                .appendPath(ZenModeConfig.NEXT_ALARM_PATH)
                .appendPath(Integer.toString(mTracker.getCurrentUserId()))
                .appendPath(Long.toString(nextAlarm.getTriggerTime()))
                .build();
    }

    private long tryParseNextAlarmCondition(Uri conditionId) {
        return conditionId != null && conditionId.getScheme().equals(Condition.SCHEME)
                && conditionId.getAuthority().equals(ZenModeConfig.SYSTEM_AUTHORITY)
                && conditionId.getPathSegments().size() == 3
                && conditionId.getPathSegments().get(0).equals(ZenModeConfig.NEXT_ALARM_PATH)
                && conditionId.getPathSegments().get(1)
                        .equals(Integer.toString(mTracker.getCurrentUserId()))
                                ? tryParseLong(conditionId.getPathSegments().get(2), BAD_CONDITION)
                                : BAD_CONDITION;
    }

    private static long tryParseLong(String value, long defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private void onEvaluate(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted) {
        final boolean withinThreshold = isWithinLookaheadThreshold(nextAlarm);
        final long nextAlarmTime = nextAlarm != null ? nextAlarm.getTriggerTime() : 0;
        if (DEBUG) Slog.d(TAG, "onEvaluate mSubscriptions=" + mSubscriptions
                + " nextAlarmTime=" +  mTracker.formatAlarmDebug(nextAlarmTime)
                + " nextAlarmWakeup=" + mTracker.formatAlarmDebug(wakeupTime)
                + " withinThreshold=" + withinThreshold
                + " booted=" + booted);

        ArraySet<Uri> conditions = mSubscriptions;
        if (mRequesting && nextAlarm != null && withinThreshold) {
            final Uri id = newConditionId(nextAlarm);
            if (!conditions.contains(id)) {
                conditions = new ArraySet<Uri>(conditions);
                conditions.add(id);
            }
        }
        for (Uri conditionId : conditions) {
            final long time = tryParseNextAlarmCondition(conditionId);
            if (time == BAD_CONDITION) {
                notifyCondition(conditionId, nextAlarm, Condition.STATE_FALSE, "badCondition");
            } else if (!booted) {
                // we don't know yet
                if (mSubscriptions.contains(conditionId)) {
                    notifyCondition(conditionId, nextAlarm, Condition.STATE_UNKNOWN, "!booted");
                }
            } else if (time != nextAlarmTime) {
                // next alarm changed since subscription, consider obsolete
                notifyCondition(conditionId, nextAlarm, Condition.STATE_FALSE, "changed");
            } else if (!withinThreshold) {
                // next alarm outside threshold or in the past, condition = false
                notifyCondition(conditionId, nextAlarm, Condition.STATE_FALSE, "!within");
            } else {
                // next alarm within threshold and in the future, condition = true
                notifyCondition(conditionId, nextAlarm, Condition.STATE_TRUE, "within");
            }
        }
    }

    private final NextAlarmTracker.Callback mTrackerCallback = new NextAlarmTracker.Callback() {
        @Override
        public void onEvaluate(AlarmClockInfo nextAlarm, long wakeupTime, boolean booted) {
            NextAlarmConditionProvider.this.onEvaluate(nextAlarm, wakeupTime, booted);
        }
    };
}
