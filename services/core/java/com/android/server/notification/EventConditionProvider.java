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
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.Condition;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.server.notification.CalendarTracker.CheckEventResult;
import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
    private static final long CHANGE_DELAY = 2 * 1000;  // coalesce chatty calendar changes

    private final Context mContext = this;
    private final ArraySet<Uri> mSubscriptions = new ArraySet<Uri>();
    private final SparseArray<CalendarTracker> mTrackers = new SparseArray<>();
    private final Handler mWorker;
    private final HandlerThread mThread;

    private boolean mConnected;
    private boolean mRegistered;
    private boolean mBootComplete;  // don't hammer the calendar provider until boot completes.
    private long mNextAlarmTime;

    public EventConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new " + SIMPLE_NAME + "()");
        mThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
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
        dumpUpcomingTime(pw, "mNextAlarmTime", mNextAlarmTime, System.currentTimeMillis());
        synchronized (mSubscriptions) {
            pw.println("      mSubscriptions=");
            for (Uri conditionId : mSubscriptions) {
                pw.print("        ");
                pw.println(conditionId);
            }
        }
        pw.println("      mTrackers=");
        for (int i = 0; i < mTrackers.size(); i++) {
            pw.print("        user="); pw.println(mTrackers.keyAt(i));
            mTrackers.valueAt(i).dump("          ", pw);
        }
    }

    @Override
    public void onBootComplete() {
        if (DEBUG) Slog.d(TAG, "onBootComplete");
        if (mBootComplete) return;
        mBootComplete = true;
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_ADDED);
        filter.addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                reloadTrackers();
            }
        }, filter);
        reloadTrackers();
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
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        if (!ZenModeConfig.isValidEventConditionId(conditionId)) {
            notifyCondition(createCondition(conditionId, Condition.STATE_FALSE));
            return;
        }
        synchronized (mSubscriptions) {
            if (mSubscriptions.add(conditionId)) {
                evaluateSubscriptions();
            }
        }
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onUnsubscribe " + conditionId);
        synchronized (mSubscriptions) {
            if (mSubscriptions.remove(conditionId)) {
                evaluateSubscriptions();
            }
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

    private void reloadTrackers() {
        if (DEBUG) Slog.d(TAG, "reloadTrackers");
        for (int i = 0; i < mTrackers.size(); i++) {
            mTrackers.valueAt(i).setCallback(null);
        }
        mTrackers.clear();
        for (UserHandle user : UserManager.get(mContext).getUserProfiles()) {
            final Context context = user.isSystem() ? mContext : getContextForUser(mContext, user);
            if (context == null) {
                Slog.w(TAG, "Unable to create context for user " + user.getIdentifier());
                continue;
            }
            mTrackers.put(user.getIdentifier(), new CalendarTracker(mContext, context));
        }
        evaluateSubscriptions();
    }

    private void evaluateSubscriptions() {
        if (!mWorker.hasCallbacks(mEvaluateSubscriptionsW)) {
            mWorker.post(mEvaluateSubscriptionsW);
        }
    }

    private void evaluateSubscriptionsW() {
        if (DEBUG) Slog.d(TAG, "evaluateSubscriptions");
        if (!mBootComplete) {
            if (DEBUG) Slog.d(TAG, "Skipping evaluate before boot complete");
            return;
        }
        final long now = System.currentTimeMillis();
        List<Condition> conditionsToNotify = new ArrayList<>();
        synchronized (mSubscriptions) {
            for (int i = 0; i < mTrackers.size(); i++) {
                mTrackers.valueAt(i).setCallback(
                        mSubscriptions.isEmpty() ? null : mTrackerCallback);
            }
            setRegistered(!mSubscriptions.isEmpty());
            long reevaluateAt = 0;
            for (Uri conditionId : mSubscriptions) {
                final EventInfo event = ZenModeConfig.tryParseEventConditionId(conditionId);
                if (event == null) {
                    conditionsToNotify.add(createCondition(conditionId, Condition.STATE_FALSE));
                    continue;
                }
                CheckEventResult result = null;
                if (event.calName == null) { // any calendar
                    // event could exist on any tracker
                    for (int i = 0; i < mTrackers.size(); i++) {
                        final CalendarTracker tracker = mTrackers.valueAt(i);
                        final CheckEventResult r = tracker.checkEvent(event, now);
                        if (result == null) {
                            result = r;
                        } else {
                            result.inEvent |= r.inEvent;
                            result.recheckAt = Math.min(result.recheckAt, r.recheckAt);
                        }
                    }
                } else {
                    // event should exist on one tracker
                    final int userId = EventInfo.resolveUserId(event.userId);
                    final CalendarTracker tracker = mTrackers.get(userId);
                    if (tracker == null) {
                        Slog.w(TAG, "No calendar tracker found for user " + userId);
                        conditionsToNotify.add(createCondition(conditionId, Condition.STATE_FALSE));
                        continue;
                    }
                    result = tracker.checkEvent(event, now);
                }
                if (result.recheckAt != 0
                        && (reevaluateAt == 0 || result.recheckAt < reevaluateAt)) {
                    reevaluateAt = result.recheckAt;
                }
                if (!result.inEvent) {
                    conditionsToNotify.add(createCondition(conditionId, Condition.STATE_FALSE));
                    continue;
                }
                conditionsToNotify.add(createCondition(conditionId, Condition.STATE_TRUE));
            }
            rescheduleAlarm(now, reevaluateAt);
        }
        for (Condition condition : conditionsToNotify) {
            if (condition != null) {
                notifyCondition(condition);
            }
        }
        if (DEBUG) Slog.d(TAG, "evaluateSubscriptions took " + (System.currentTimeMillis() - now));
    }

    private void rescheduleAlarm(long now, long time) {
        mNextAlarmTime = time;
        final AlarmManager alarms = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext,
                REQUEST_CODE_EVALUATE,
                new Intent(ACTION_EVALUATE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_TIME, time),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
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

    private static Context getContextForUser(Context context, UserHandle user) {
        try {
            return context.createPackageContextAsUser(context.getPackageName(), 0, user);
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private final CalendarTracker.Callback mTrackerCallback = new CalendarTracker.Callback() {
        @Override
        public void onChanged() {
            if (DEBUG) Slog.d(TAG, "mTrackerCallback.onChanged");
            mWorker.removeCallbacks(mEvaluateSubscriptionsW);
            mWorker.postDelayed(mEvaluateSubscriptionsW, CHANGE_DELAY);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "onReceive " + intent.getAction());
            evaluateSubscriptions();
        }
    };

    private final Runnable mEvaluateSubscriptionsW = new Runnable() {
        @Override
        public void run() {
            evaluateSubscriptionsW();
        }
    };
}
