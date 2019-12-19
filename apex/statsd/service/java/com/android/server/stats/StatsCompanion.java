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

import android.content.Context;
import android.util.Slog;

import com.android.server.SystemService;

/**
 * @hide
 */
public class StatsCompanion {
    private static final String TAG = "StatsCompanion";
    private static final boolean DEBUG = false;

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
                mStatsManagerService.systemReady();
            }
        }
    }
}
