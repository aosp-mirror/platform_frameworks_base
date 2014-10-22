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
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.service.notification.Condition;
import android.service.notification.ConditionProviderService;
import android.service.notification.IConditionProvider;
import android.service.notification.ZenModeConfig;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Slog;

import com.android.server.notification.NotificationManagerService.DumpFilter;

import java.io.PrintWriter;
import java.util.Date;

/** Built-in zen condition provider for simple time-based conditions */
public class CountdownConditionProvider extends ConditionProviderService {
    private static final String TAG = "CountdownConditions";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final ComponentName COMPONENT =
            new ComponentName("android", CountdownConditionProvider.class.getName());

    private static final String ACTION = CountdownConditionProvider.class.getName();
    private static final int REQUEST_CODE = 100;
    private static final String EXTRA_CONDITION_ID = "condition_id";

    private final Context mContext = this;
    private final Receiver mReceiver = new Receiver();

    private boolean mConnected;
    private long mTime;

    public CountdownConditionProvider() {
        if (DEBUG) Slog.d(TAG, "new CountdownConditionProvider()");
    }

    public void dump(PrintWriter pw, DumpFilter filter) {
        pw.println("    CountdownConditionProvider:");
        pw.print("      mConnected="); pw.println(mConnected);
        pw.print("      mTime="); pw.println(mTime);
    }

    @Override
    public void onConnected() {
        if (DEBUG) Slog.d(TAG, "onConnected");
        mContext.registerReceiver(mReceiver, new IntentFilter(ACTION));
        mConnected = true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DEBUG) Slog.d(TAG, "onDestroy");
        if (mConnected) {
            mContext.unregisterReceiver(mReceiver);
        }
        mConnected = false;
    }

    @Override
    public void onRequestConditions(int relevance) {
        // by convention
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        if (DEBUG) Slog.d(TAG, "onSubscribe " + conditionId);
        mTime = ZenModeConfig.tryParseCountdownConditionId(conditionId);
        final AlarmManager alarms = (AlarmManager)
                mContext.getSystemService(Context.ALARM_SERVICE);
        final Intent intent = new Intent(ACTION).putExtra(EXTRA_CONDITION_ID, conditionId)
                .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, REQUEST_CODE,
                intent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pendingIntent);
        if (mTime > 0) {
            final long now = System.currentTimeMillis();
            final CharSequence span =
                    DateUtils.getRelativeTimeSpanString(mTime, now, DateUtils.MINUTE_IN_MILLIS);
            if (mTime <= now) {
                // in the past, already false
                notifyCondition(newCondition(mTime, Condition.STATE_FALSE));
            } else {
                // in the future, set an alarm
                alarms.setExact(AlarmManager.RTC_WAKEUP, mTime, pendingIntent);
            }
            if (DEBUG) Slog.d(TAG, String.format(
                    "%s %s for %s, %s in the future (%s), now=%s",
                    (mTime <= now ? "Not scheduling" : "Scheduling"),
                    ACTION, ts(mTime), mTime - now, span, ts(now)));
        }
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        // noop
    }

    private final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION.equals(intent.getAction())) {
                final Uri conditionId = intent.getParcelableExtra(EXTRA_CONDITION_ID);
                final long time = ZenModeConfig.tryParseCountdownConditionId(conditionId);
                if (DEBUG) Slog.d(TAG, "Countdown condition fired: " + conditionId);
                if (time > 0) {
                    notifyCondition(newCondition(time, Condition.STATE_FALSE));
                }
            }
        }
    }

    private static final Condition newCondition(long time, int state) {
        return new Condition(ZenModeConfig.toCountdownConditionId(time),
                "", "", "", 0, state,Condition.FLAG_RELEVANT_NOW);
    }

    public static String tryParseDescription(Uri conditionUri) {
        final long time = ZenModeConfig.tryParseCountdownConditionId(conditionUri);
        if (time == 0) return null;
        final long now = System.currentTimeMillis();
        final CharSequence span =
                DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS);
        return String.format("Scheduled for %s, %s in the future (%s), now=%s",
                ts(time), time - now, span, ts(now));
    }

    private static String ts(long time) {
        return new Date(time) + " (" + time + ")";
    }

    public void attachBase(Context base) {
        attachBaseContext(base);
    }

    public IConditionProvider asInterface() {
        return (IConditionProvider) onBind(null);
    }
}
