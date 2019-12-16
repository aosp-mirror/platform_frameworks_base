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
import android.content.Intent;
import android.os.Binder;
import android.os.IPendingIntentRef;
import android.os.Process;
import android.os.StatsDimensionsValue;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * @hide
 */
public class StatsCompanion {
    private static final String TAG = "StatsCompanion";
    private static final boolean DEBUG = false;

    static void enforceStatsCompanionPermission(Context context) {
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        context.enforceCallingPermission(android.Manifest.permission.STATSCOMPANION, null);
    }

    /**
     * Lifecycle class for both {@link StatsCompanionService} and {@link StatsManagerService}.
     */
    public static final class Lifecycle extends SystemService {
        private StatsCompanionService mStatsCompanionService;
        private StatsManagerService mStatsManagerService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mStatsCompanionService = new StatsCompanionService(getContext());
            mStatsManagerService = new StatsManagerService(getContext());
            mStatsCompanionService.setStatsManagerService(mStatsManagerService);
            mStatsManagerService.setStatsCompanionService(mStatsCompanionService);

            try {
                publishBinderService(Context.STATS_COMPANION_SERVICE,
                        mStatsCompanionService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_COMPANION_SERVICE);
                publishBinderService(Context.STATS_MANAGER_SERVICE,
                        mStatsManagerService);
                if (DEBUG) Slog.d(TAG, "Published " + Context.STATS_MANAGER_SERVICE);
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

    /**
     * Wrapper for {@link PendingIntent}. Allows Statsd to send PendingIntents.
     */
    public static class PendingIntentRef extends IPendingIntentRef.Stub {

        private static final String TAG = "PendingIntentRef";

        /**
         * The last report time is provided with each intent registered to
         * StatsManager#setFetchReportsOperation. This allows easy de-duping in the receiver if
         * statsd is requesting the client to retrieve the same statsd data. The last report time
         * corresponds to the last_report_elapsed_nanos that will provided in the current
         * ConfigMetricsReport, and this timestamp also corresponds to the
         * current_report_elapsed_nanos of the most recently obtained ConfigMetricsReport.
         */
        private static final String EXTRA_LAST_REPORT_TIME = "android.app.extra.LAST_REPORT_TIME";
        private static final int CODE_DATA_BROADCAST = 1;

        private final PendingIntent mPendingIntent;
        private final Context mContext;

        public PendingIntentRef(PendingIntent pendingIntent, Context context) {
            mPendingIntent = pendingIntent;
            mContext = context;
        }

        @Override
        public void sendDataBroadcast(long lastReportTimeNs) {
            enforceStatsCompanionPermission(mContext);
            Intent intent = new Intent();
            intent.putExtra(EXTRA_LAST_REPORT_TIME, lastReportTimeNs);
            try {
                mPendingIntent.send(mContext, CODE_DATA_BROADCAST, intent, null, null);
            } catch (PendingIntent.CanceledException e) {
                Slog.w(TAG, "Unable to send PendingIntent");
            }
        }

        @Override
        public void sendActiveConfigsChangedBroadcast(long[] configIds) {
            // no-op
        }

        @Override
        public void sendSubscriberBroadcast(long configUid, long configId, long subscriptionId,
                long subscriptionRuleId, String[] cookies, StatsDimensionsValue dimensionsValue) {
            // no-op
        }
    }
}
