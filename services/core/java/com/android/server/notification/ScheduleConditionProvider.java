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

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.ScheduleCalendar;
import android.service.notification.ZenModeConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Built-in zen condition provider for daily scheduled time-based conditions.
 */
public class ScheduleConditionProvider extends SystemConditionProviderService {
    static final String TAG = "ConditionProviders.SCP";
    static final boolean DEBUG = true || Log.isLoggable("ConditionProviders", Log.DEBUG);

    public static final ComponentName COMPONENT =
            new ComponentName("android", ScheduleConditionProvider.class.getName());
    private static final String NOT_SHOWN = "...";
    private static final String SIMPLE_NAME = ScheduleConditionProvider.class.getSimpleName();
    private static final String ACTION_EVALUATE =  SIMPLE_NAME + ".EVALUATE";
    private static final int REQUEST_CODE_EVALUATE = 1;
    private static final String EXTRA_TIME = "time";
    private static final String SEPARATOR = ";";
    private static final String SCP_SETTING = "snoozed_schedule_condition_provider";

    private final Context mContext = this;
    private final ArrayMap<Uri, ScheduleCalendar> mSubscriptions = new ArrayMap<>();
    private ArraySet<Uri> mSnoozedForAlarm = new ArraySet<>();

    private AlarmManager mAlarmManager;
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
        synchronized (mSubscriptions) {
            for (Uri conditionId : mSubscriptions.keySet()) {
                pw.print("        ");
                pw.print(meetsSchedule(mSubscriptions.get(conditionId), now) ? "* " : "  ");
                pw.println(conditionId);
                pw.print("            ");
                pw.println(mSubscriptions.get(conditionId).toString());
            }
        }
        pw.println("      snoozed due to alarm: " + TextUtils.join(SEPARATOR, mSnoozedForAlarm));
        dumpUpcomingTime(pw, "mNextAlarmTime", mNextAlarmTime, now);
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mConnected = true;
        readSnoozed();
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
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        if (!ZenModeConfig.isValidScheduleConditionId(conditionId)) {
            notifyCondition(createCondition(conditionId, Condition.STATE_ERROR, "invalidId"));
            return;
        }
        synchronized (mSubscriptions) {
            mSubscriptions.put(conditionId, ZenModeConfig.toScheduleCalendar(conditionId));
        }
        evaluateSubscriptions();
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        synchronized (mSubscriptions) {
            mSubscriptions.remove(conditionId);
        }
        removeSnoozed(conditionId);
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
        if (mAlarmManager == null) {
            mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        }
        final long now = System.currentTimeMillis();
        mNextAlarmTime = 0;
        long nextUserAlarmTime = getNextAlarm();
        List<Condition> conditionsToNotify = new ArrayList<>();
        synchronized (mSubscriptions) {
            setRegistered(!mSubscriptions.isEmpty());
            for (Uri conditionId : mSubscriptions.keySet()) {
                Condition condition =
                        evaluateSubscriptionLocked(conditionId, mSubscriptions.get(conditionId),
                                now, nextUserAlarmTime);
                if (condition != null) {
                    conditionsToNotify.add(condition);
                }
            }
        }
        notifyConditions(conditionsToNotify.toArray(new Condition[conditionsToNotify.size()]));
        updateAlarm(now, mNextAlarmTime);
    }

    @VisibleForTesting
    @GuardedBy("mSubscriptions")
    Condition evaluateSubscriptionLocked(Uri conditionId, ScheduleCalendar cal,
            long now, long nextUserAlarmTime) {
        if (DEBUG) Slog.d(TAG, String.format("evaluateSubscriptionLocked cal=%s, now=%s, "
                        + "nextUserAlarmTime=%s", cal, ts(now), ts(nextUserAlarmTime)));
        Condition condition;
        if (cal == null) {
            condition = createCondition(conditionId, Condition.STATE_ERROR, "!invalidId");
            removeSnoozed(conditionId);
            return condition;
        }
        if (cal.isInSchedule(now)) {
            if (conditionSnoozed(conditionId)) {
                condition = createCondition(conditionId, Condition.STATE_FALSE, "snoozed");
            } else if (cal.shouldExitForAlarm(now)) {
                condition = createCondition(conditionId, Condition.STATE_FALSE, "alarmCanceled");
                addSnoozed(conditionId);
            } else {
                condition = createCondition(conditionId, Condition.STATE_TRUE, "meetsSchedule");
            }
        } else {
            condition = createCondition(conditionId, Condition.STATE_FALSE, "!meetsSchedule");
            removeSnoozed(conditionId);
        }
        cal.maybeSetNextAlarm(now, nextUserAlarmTime);
        final long nextChangeTime = cal.getNextChangeTime(now);
        if (nextChangeTime > 0 && nextChangeTime > now) {
            if (mNextAlarmTime == 0 || nextChangeTime < mNextAlarmTime) {
                mNextAlarmTime = nextChangeTime;
            }
        }
        return condition;
    }

    private void updateAlarm(long now, long time) {
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_EVALUATE,
                new Intent(ACTION_EVALUATE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_TIME, time),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.cancel(pendingIntent);
        if (time > now) {
            if (DEBUG) Slog.d(TAG, String.format("Scheduling evaluate for %s, in %s, now=%s",
                    ts(time), formatDuration(time - now), ts(now)));
            alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
        } else {
            if (DEBUG) Slog.d(TAG, "Not scheduling evaluate");
        }
    }

    public long getNextAlarm() {
        final AlarmManager.AlarmClockInfo info = mAlarmManager.getNextAlarmClock(
                ActivityManager.getCurrentUser());
        return info != null ? info.getTriggerTime() : 0;
    }

    private boolean meetsSchedule(ScheduleCalendar cal, long time) {
        return cal != null && cal.isInSchedule(time);
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
            filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
            registerReceiver(mReceiver, filter);
        } else {
            unregisterReceiver(mReceiver);
        }
    }

    private Condition createCondition(Uri id, int state, String reason) {
        if (DEBUG) Slog.d(TAG, "notifyCondition " + id
                + " " + Condition.stateToString(state)
                + " reason=" + reason);
        final String summary = NOT_SHOWN;
        final String line1 = NOT_SHOWN;
        final String line2 = NOT_SHOWN;
        return new Condition(id, summary, line1, line2, 0, state, Condition.FLAG_RELEVANT_ALWAYS);
    }

    private boolean conditionSnoozed(Uri conditionId) {
        synchronized (mSnoozedForAlarm) {
            return mSnoozedForAlarm.contains(conditionId);
        }
    }

    @VisibleForTesting
    void addSnoozed(Uri conditionId) {
        synchronized (mSnoozedForAlarm) {
            mSnoozedForAlarm.add(conditionId);
            saveSnoozedLocked();
        }
    }

    private void removeSnoozed(Uri conditionId) {
        synchronized (mSnoozedForAlarm) {
            mSnoozedForAlarm.remove(conditionId);
            saveSnoozedLocked();
        }
    }

    private void saveSnoozedLocked() {
        final String setting = TextUtils.join(SEPARATOR, mSnoozedForAlarm);
        final int currentUser = ActivityManager.getCurrentUser();
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                SCP_SETTING,
                setting,
                currentUser);
    }

    private void readSnoozed() {
        synchronized (mSnoozedForAlarm) {
            final long identity = Binder.clearCallingIdentity();
            try {
                final String setting = Settings.Secure.getStringForUser(
                        mContext.getContentResolver(),
                        SCP_SETTING,
                        ActivityManager.getCurrentUser());
                if (setting != null) {
                    final String[] tokens = setting.split(SEPARATOR);
                    for (int i = 0; i < tokens.length; i++) {
                        String token = tokens[i];
                        if (token != null) {
                            token = token.trim();
                        }
                        if (TextUtils.isEmpty(token)) {
                            continue;
                        }
                        mSnoozedForAlarm.add(Uri.parse(token));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "onReceive " + intent.getAction());
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                synchronized (mSubscriptions) {
                    for (Uri conditionId : mSubscriptions.keySet()) {
                        final ScheduleCalendar cal = mSubscriptions.get(conditionId);
                        if (cal != null) {
                            cal.setTimeZone(Calendar.getInstance().getTimeZone());
                        }
                    }
                }
            }
            evaluateSubscriptions();
        }
    };

}
