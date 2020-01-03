/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.PendingIntent;
import android.content.Context;
import android.os.IBinder;
import android.os.IStatsManagerService;
import android.os.IStatsd;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

/**
 * @hide
 */
public class StatsManagerService extends IStatsManagerService.Stub {

    private static final String TAG = "StatsManagerService";
    private static final boolean DEBUG = false;

    @GuardedBy("sStatsdLock")
    private static IStatsd sStatsd;
    private static final Object sStatsdLock = new Object();

    private StatsCompanionService mStatsCompanionService;

    public StatsManagerService(Context context) {
        super();
    }

    @Override
    public void setDataFetchOperation(long configKey, PendingIntent pendingIntent,
            String packageName) {
        // no-op
        if (DEBUG) {
            Slog.d(TAG, "setDataFetchOperation");
        }
    }

    @Override
    public long[] setActiveConfigsChangedOperation(PendingIntent pendingIntent,
            String packageName) {
        // no-op
        if (DEBUG) {
            Slog.d(TAG, "setActiveConfigsChangedOperation");
        }
        return new long[]{};
    }

    @Override
    public void setBroadcastSubscriber(long configKey, long subscriberId,
            PendingIntent pendingIntent, String packageName) {
        //no-op
        if (DEBUG) {
            Slog.d(TAG, "setBroadcastSubscriber");
        }
    }

    void setStatsCompanionService(StatsCompanionService statsCompanionService) {
        mStatsCompanionService = statsCompanionService;
    }

    void systemReady() {
        if (DEBUG) {
            Slog.d(TAG, "statsdReady");
        }
        setupStatsManagerService();
    }

    private void setupStatsManagerService() {
        synchronized (sStatsdLock) {
            if (sStatsd != null) {
                if (DEBUG) {
                    Slog.e(TAG, "Trying to fetch statsd, but it was already fetched",
                            new IllegalStateException(
                                    "sStatsd is not null when being fetched"));
                }
                return;
            }
            sStatsd = IStatsd.Stub.asInterface(ServiceManager.getService("stats"));
            if (sStatsd == null) {
                if (DEBUG) {
                    Slog.d(TAG, "Failed to get stats service.");
                }
                return;
            }
            // Assume statsd is ready since this is called form statscompanion, link to statsd.
            try {
                sStatsd.asBinder().linkToDeath((IBinder.DeathRecipient) () -> {
                    sStatsd = null;
                }, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "linkToDeath(StatsdDeathRecipient) failed", e);
            }
        }
    }
}
