/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.stats;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IStatsCompanionService;
import android.os.IStatsManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {
    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = true;

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private static IStatsManager sStatsd;

    private final PendingIntent mAnomalyAlarmIntent;
    private final PendingIntent mPollingAlarmIntent;

    public final static class AnomalyAlarmReceiver extends BroadcastReceiver  {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.i(TAG, "StatsCompanionService believes an anomaly has occurred.");
            try {
                // TODO: should be twoway so device won't sleep before acting?
                getStatsdService().informAnomalyAlarmFired();
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to inform statsd of anomaly alarm firing", e);
            } catch (NullPointerException e) {
                Slog.e(TAG, "could not access statsd to inform it of anomaly alarm firing", e);
            }
            // AlarmManager releases its own wakelock here.
        }
    };

    public final static class PollingAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "Time to poll something.");
            if (DEBUG) Slog.d(TAG, "Time to poll something.");
            try {
                // TODO: should be twoway so device won't sleep before acting?
                getStatsdService().informPollAlarmFired();
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to inform statsd of polling alarm firing",e);
            } catch (NullPointerException e) {
                Slog.e(TAG, "could not access statsd to inform it of polling alarm firing", e);
            }
            // AlarmManager releases its own wakelock here.
        }
    };

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mAnomalyAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, AnomalyAlarmReceiver.class), 0);
        mPollingAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, PollingAlarmReceiver.class), 0);
    }

    /** Returns the statsd IBinder service */
    public static IStatsManager getStatsdService() {
        if (sStatsd != null) {
            return sStatsd;
        }
        sStatsd = IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
        return sStatsd;
    }

    public static final class Lifecycle extends SystemService {
        private StatsCompanionService mStatsCompanionService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mStatsCompanionService = new StatsCompanionService(getContext());
            try {
                publishBinderService(Context.STATS_COMPANION_SERVICE, mStatsCompanionService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_COMPANION_SERVICE);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to publishBinderService", e);
            }
        }
    }

    @Override // Binder call
    public void setAnomalyAlarm(long timestampMs) {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Setting anomaly alarm for " + timestampMs);
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using RTC, not RTC_WAKEUP, so if device is asleep, will only fire when it awakens.
            // This alarm is inexact, leaving its exactness completely up to the OS optimizations.
            // AlarmManager will automatically cancel any previous mAnomalyAlarmIntent alarm.
            mAlarmManager.set(AlarmManager.RTC, timestampMs, mAnomalyAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelAnomalyAlarm() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Cancelling anomaly alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mAnomalyAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void setPollingAlarms(long timestampMs, long intervalMs) {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Setting polling alarm for " + timestampMs
                + " every " + intervalMs + "ms");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            // using RTC, not RTC_WAKEUP, so if device is asleep, will only fire when it awakens.
            // This alarm is inexact, leaving its exactness completely up to the OS optimizations.
            // TODO: totally inexact means that stats per bucket could be quite off. Is this okay?
            mAlarmManager.setRepeating(AlarmManager.RTC, timestampMs, intervalMs,
                    mPollingAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    @Override // Binder call
    public void cancelPollingAlarms() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "Cancelling polling alarm");
        final long callingToken = Binder.clearCallingIdentity();
        try {
            mAlarmManager.cancel(mPollingAlarmIntent);
        } finally {
            Binder.restoreCallingIdentity(callingToken);
        }
    }

    private void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforceCallingPermission(android.Manifest.permission.STATSCOMPANION, null);
    }

}
