/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.media.metrics;

import android.content.Context;
import android.media.metrics.IPlaybackMetricsManager;
import android.media.metrics.PlaybackMetrics;

import com.android.server.SystemService;

/**
 * System service manages playback metrics.
 */
public final class PlaybackMetricsManagerService extends SystemService {

    /**
     * Initializes the playback metrics manager service.
     *
     * @param context The system server context.
     */
    public PlaybackMetricsManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        // TODO: make the service name a constant in Context.java
        publishBinderService("playback_metrics", new BinderService());
    }

    private final class BinderService extends IPlaybackMetricsManager.Stub {
        @Override
        public void reportPlaybackMetrics(PlaybackMetrics metrics, int userId) {
            // TODO: log it to statsd
        }
    }
}
