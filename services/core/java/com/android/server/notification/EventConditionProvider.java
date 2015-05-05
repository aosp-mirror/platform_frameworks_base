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
import android.service.notification.ZenModeConfig.EventInfo;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.server.notification.CalendarTracker.CheckEventResult;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;

/**
 * Built-in zen condition provider for calendar event-based conditions.
 */
public class EventConditionProvider extends SystemConditionProviderService {
    private static final String TAG = "ConditionProviders.ECP";
    private static final boolean DEBUG = Log.isLoggable("ConditionProviders", Log.DEBUG);

    public static final ComponentName COMPONENT =
            new ComponentName("android", EventConditionProvider.class.getName());
    private static final String NOT_SHOWN = "...";
    private static final String SIMPLE_NAME = EventConditionProvider.class.getSimpleName();
    private static final String ACTION_EVALUATE = SIMPLE_NAME + ".EVALUATE";
    private static final int REQUEST_CODE_EVALUATE = 1;
    private static final String EXTRA_TIME = "time";

    private final Context mContext = this;
    private final ArraySet<Uri> mSubscriptions = new ArraySet<Uri>();
    private final CalendarTracker mTracker = new CalendarTracker(mContext);

    private boolean mConnected;
    private boolean mRegistered;
    private boolean mBootComplete;  // don't hammer the calendar provider until boot completes.

    public EventConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new " + SIMPLE_NAME + "()");
    }

    @Override
    public ComponentName getComponent() {
        return COMPONENT;
    }

    @Override
    public boolean isValidConditionId(Uri id) {
        return ZenModeConfig.isValidEventConditionId(id);
    }

    @Override
    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.print("    "); pw.print(SIMPLE_NAME); pw.println(":");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mRegistered="); pw.println(mRegistered);
        pw.print("      mBootComplete="); pw.println(mBootComplete);
        pw.println("      mSubscriptions=");
        for (Uri conditionId : mSubscriptions) {
            pw.print("        ");
            pw.println(conditionId);
        }
        pw.println("      mTracker=");
        mTracker.dump("        ", pw);
    }

    @Override
    public void onBootComplete() {
        if (DEBUG) Slog.d(TAG, "onBootComplete");
        if (mBootComplete) return;
        mBootComplete = true;
        evaluateSubscriptions();
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mConnected = true;
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
        if (!ZenModeConfig.isValidEventConditionId(conditionId)) {
            notifyCondition(conditionId, Condition.STATE_FALSE, "badCondition");
            return;
        }
        if (mSubscriptions.add(conditionId)) {
            evaluateSubscriptions();
        }
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        if (mSubscriptions.remove(conditionId)) {
            evaluateSubscriptions();
        }
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
        if (DEBUG) Log.d(TAG, "evaluateSubscriptions");
        if (!mBootComplete) {
            if (DEBUG) Log.d(TAG, "Skipping evaluate before boot complete");
            return;
        }
        final long now = System.currentTimeMillis();
        mTracker.setCallback(mSubscriptions.isEmpty() ? null : mTrackerCallback);
        setRegistered(!mSubscriptions.isEmpty());
        long reevaluateAt = 0;
        for (Uri conditionId : mSubscriptions) {
            final EventInfo event = ZenModeConfig.tryParseEventConditionId(conditionId);
            if (event == null) {
                notifyCondition(conditionId, Condition.STATE_FALSE, "badConditionId");
                continue;
            }
            final CheckEventResult result = mTracker.checkEvent(event, now);
            if (result.recheckAt != 0 && (reevaluateAt == 0 || result.recheckAt < reevaluateAt)) {
                reevaluateAt = result.recheckAt;
            }
            if (!result.inEvent) {
                notifyCondition(conditionId, Condition.STATE_FALSE, "!inEventNow");
                continue;
            }
            notifyCondition(conditionId, Condition.STATE_TRUE, "inEventNow");
        }
        updateAlarm(now, reevaluateAt);
        if (DEBUG) Log.d(TAG, "evaluateSubscriptions took " + (System.currentTimeMillis() - now));
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
        if (time == 0 || time < now) {
            if (DEBUG) Slog.d(TAG, "Not scheduling evaluate: " + (time == 0 ? "no time specified"
                    : "specified time in the past"));
            return;
        }
        if (DEBUG) Slog.d(TAG, String.format("Scheduling evaluate for %s, in %s, now=%s",
                ts(time), formatDuration(time - now), ts(now)));
        alarms.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent);
    }

    private void notifyCondition(Uri conditionId, int state, String reason) {
        if (DEBUG) Slog.d(TAG, "notifyCondition " + Condition.stateToString(state)
                + " reason=" + reason);
        notifyCondition(createCondition(conditionId, state));
    }

    private Condition createCondition(Uri id, int state) {
        final String summary = NOT_SHOWN;
        final String line1 = NOT_SHOWN;
        final String line2 = NOT_SHOWN;
        return new Condition(id, summary, line1, line2, 0, state, Condition.FLAG_RELEVANT_ALWAYS);
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

    private final CalendarTracker.Callback mTrackerCallback = new CalendarTracker.Callback() {
        @Override
        public void onChanged() {
            if (DEBUG) Log.d(TAG, "mTrackerCallback.onChanged");
            evaluateSubscriptions();
        }
    };

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "onReceive " + intent.getAction());
            evaluateSubscriptions();
        }
    };
}
