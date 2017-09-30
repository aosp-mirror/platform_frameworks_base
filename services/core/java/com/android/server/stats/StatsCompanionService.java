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
import android.os.IBinder;
import android.os.IStatsCompanionService;
import android.os.IStatsManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

/**
 * Helper service for statsd (the native stats management service in cmds/statsd/).
 * Used for registering and receiving alarms on behalf of statsd.
 * @hide
 */
public class StatsCompanionService extends IStatsCompanionService.Stub {
    static final String TAG = "StatsCompanionService";
    static final boolean DEBUG = true;

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    @GuardedBy("sStatsdLock")
    private static IStatsManager sStatsd;
    private static final Object sStatsdLock = new Object();

    private final PendingIntent mAnomalyAlarmIntent;
    private final PendingIntent mPollingAlarmIntent;

    public StatsCompanionService(Context context) {
        super();
        mContext = context;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);

        mAnomalyAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, AnomalyAlarmReceiver.class), 0);
        mPollingAlarmIntent = PendingIntent.getBroadcast(mContext, 0,
                new Intent(mContext, PollingAlarmReceiver.class), 0);
    }

    public final static class AnomalyAlarmReceiver extends BroadcastReceiver  {
        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.i(TAG, "StatsCompanionService believes an anomaly has occurred.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of anomaly alarm firing");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informAnomalyAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of anomaly alarm firing", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    };

    public final static class PollingAlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Slog.d(TAG, "Time to poll something.");
            synchronized (sStatsdLock) {
                if (sStatsd == null) {
                    Slog.w(TAG, "Could not access statsd to inform it of polling alarm firing");
                    return;
                }
                try {
                    // Two-way call to statsd to retain AlarmManager wakelock
                    sStatsd.informPollAlarmFired();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Failed to inform statsd of polling alarm firing", e);
                }
            }
            // AlarmManager releases its own wakelock here.
        }
    };

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

    @Override
    public void statsdReady() {
        enforceCallingPermission();
        if (DEBUG) Slog.d(TAG, "learned that statsdReady");
        sayHiToStatsd(); // tell statsd that we're ready too and link to it
    }

    private void enforceCallingPermission() {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforceCallingPermission(android.Manifest.permission.STATSCOMPANION, null);
    }

    // Lifecycle and related code

    /** Fetches the statsd IBinder service */
    private static IStatsManager fetchStatsdService() {
        return IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
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

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                mStatsCompanionService.systemReady();
            }
        }
    }

    /** Now that the android system is ready, StatsCompanion is ready too, so inform statsd. */
    private void systemReady() {
        if (DEBUG) Slog.d(TAG, "Learned that systemReady");
        sayHiToStatsd();
    }

    /** Tells statsd that statscompanion is ready. If the binder call returns, link to statsd. */
    private void sayHiToStatsd() {
        synchronized (sStatsdLock) {
            if (sStatsd != null) {
                Slog.e(TAG, "Trying to fetch statsd, but it was already fetched",
                        new IllegalStateException("sStatsd is not null when being fetched"));
                return;
            }
            sStatsd = fetchStatsdService();
            if (sStatsd == null) {
                Slog.w(TAG, "Could not access statsd");
                return;
            }
            if (DEBUG) Slog.d(TAG, "Saying hi to statsd");
            try {
                sStatsd.statsCompanionReady();
                // If the statsCompanionReady two-way binder call returns, link to statsd.
                try {
                    sStatsd.asBinder().linkToDeath(new StatsdDeathRecipient(), 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "linkToDeath(StatsdDeathRecipient) failed", e);
                    forgetEverything();
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to inform statsd that statscompanion is ready", e);
                forgetEverything();
            }
        }
    }

    private class StatsdDeathRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            Slog.i(TAG, "Statsd is dead - erase all my knowledge.");
            forgetEverything();
        }
    }

    private void forgetEverything() {
        synchronized (sStatsdLock) {
            sStatsd = null;
            cancelAnomalyAlarm();
            cancelPollingAlarms();
        }
    }

}
