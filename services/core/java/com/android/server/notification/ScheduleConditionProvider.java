/*
 * Copyright (C) 2015 The Android Open Source Project
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
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.TimeZone;

/**
 * Built-in zen condition provider for daily scheduled time-based conditions.
 */
public class ScheduleConditionProvider extends SystemConditionProviderService {
    private static final String TAG = "ConditionProviders.SCP";
    private static final boolean DEBUG = Log.isLoggable("ConditionProviders", Log.DEBUG);

    public static final ComponentName COMPONENT =
            new ComponentName("android", ScheduleConditionProvider.class.getName());
    private static final String NOT_SHOWN = "...";
    private static final String SIMPLE_NAME = ScheduleConditionProvider.class.getSimpleName();
    private static final String ACTION_EVALUATE =  SIMPLE_NAME + ".EVALUATE";
    private static final int REQUEST_CODE_EVALUATE = 1;
    private static final String EXTRA_TIME = "time";

    private final Context mContext = this;
    private final ArraySet<Uri> mSubscriptions = new ArraySet<Uri>();

    private boolean mConnected;
    private boolean mRegistered;
    private long mNextAlarmTime;

    public ScheduleConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new " + SIMPLE_NAME + "()");
    }

    @Override
    public ComponentName getComponent() {
        return COMPONENT;
    }

    @Override
    public boolean isValidConditionId(Uri id) {
        return ZenModeConfig.isValidScheduleConditionId(id);
    }

    @Override
    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.print("    "); pw.print(SIMPLE_NAME); pw.println(":");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mRegistered="); pw.println(mRegistered);
        pw.println("      mSubscriptions=");
        final long now = System.currentTimeMillis();
        for (Uri conditionId : mSubscriptions) {
            pw.print("        ");
            pw.print(meetsSchedule(conditionId, now) ? "* " : "  ");
            pw.println(conditionId);
        }
        dumpUpcomingTime(pw, "mNextAlarmTime", mNextAlarmTime, now);
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mConnected = true;
    }

    @Override
    public void onBootComplete() {
        // noop
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DEBUG) Slog.d(TAG, "onDestroy");
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        if (DEBUG) Slog.d(TAG, "onRequestConditions relevance=" + relevance);
        // does not advertise conditions
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        if (!ZenModeConfig.isValidScheduleConditionId(conditionId)) {
            notifyCondition(conditionId, Condition.STATE_FALSE, "badCondition");
            return;
        }
        mSubscriptions.add(conditionId);
        evaluateSubscriptions();
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        mSubscriptions.remove(conditionId);
        evaluateSubscriptions();
    }

    @Override
    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    @Override
    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }

    private void evaluateSubscriptions() {
        setRegistered(!mSubscriptions.isEmpty());
        final long now = System.currentTimeMillis();
        mNextAlarmTime = 0;
        for (Uri conditionId : mSubscriptions) {
            final ScheduleCalendar cal = toScheduleCalendar(conditionId);
            if (cal != null && cal.isInSchedule(now)) {
                notifyCondition(conditionId, Condition.STATE_TRUE, "meetsSchedule");
            } else {
                notifyCondition(conditionId, Condition.STATE_FALSE, "!meetsSchedule");
            }
            if (cal != null) {
                final long nextChangeTime = cal.getNextChangeTime(now);
                if (nextChangeTime > 0 && nextChangeTime > now) {
                    if (mNextAlarmTime == 0 || nextChangeTime < mNextAlarmTime) {
                        mNextAlarmTime = nextChangeTime;
                    }
                }
            }
        }
        updateAlarm(now, mNextAlarmTime);
    }

    private void updateAlarm(long now, long time) {
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_EVALUATE,
                new Intent(ACTION_EVALUATE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_TIME, time),
                PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        if (time > now) {
            if (DEBUG) Slog.d(TAG, String.format("Scheduling evaluate for %s, in %s, now=%s",
                    ts(time), formatDuration(time - now), ts(now)));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        } else {
            if (DEBUG) Slog.d(TAG, "Not scheduling evaluate");
        }
    }

    private static boolean meetsSchedule(Uri conditionId, long time) {
        final ScheduleCalendar cal = toScheduleCalendar(conditionId);
        return cal != null && cal.isInSchedule(time);
    }

    private static ScheduleCalendar toScheduleCalendar(Uri conditionId) {
        final ScheduleInfo schedule = ZenModeConfig.tryParseScheduleConditionId(conditionId);
        if (schedule == null || schedule.days == null || schedule.days.length == 0) return null;
        final ScheduleCalendar sc = new ScheduleCalendar();
        sc.setSchedule(schedule);
        sc.setTimeZone(TimeZone.getDefault());
        return sc;
    }

    private void setRegistered(boolean registered) {
        if (mRegistered == registered) return;
        if (DEBUG) Slog.d(TAG, "setRegistered " + registered);
        mRegistered = registered;
        if (mRegistered) {
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(ACTION_EVALUATE);
            registerReceiver(mReceiver, filter);
        } else {
            unregisterReceiver(mReceiver);
        }
    }

    private void notifyCondition(Uri conditionId, int state, String reason) {
        if (DEBUG) Slog.d(TAG, "notifyCondition " + conditionId
                + " " + Condition.stateToString(state)
                + " reason=" + reason);
        notifyCondition(createCondition(conditionId, state));
    }

    private Condition createCondition(Uri id, int state) {
        final String summary = NOT_SHOWN;
        final String line1 = NOT_SHOWN;
        final String line2 = NOT_SHOWN;
        return new Condition(id, summary, line1, line2, 0, state, Condition.FLAG_RELEVANT_ALWAYS);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "onReceive " + intent.getAction());
            evaluateSubscriptions();
        }
    };

}
