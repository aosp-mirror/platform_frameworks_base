/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.media.metrics;

import android.annotation.NonNull;
import android.os.RemoteException;

/**
 * @hide
 */
public class PlaybackMetricsManager {
    // TODO: unhide APIs.
    private static final String TAG = "PlaybackMetricsManager";

    private IPlaybackMetricsManager mService;
    private int mUserId;

    /**
     * @hide
     */
    public PlaybackMetricsManager(IPlaybackMetricsManager service, int userId) {
        mService = service;
        mUserId = userId;
    }

    /**
     * Reports playback metrics.
     * @hide
     */
    public void reportPlaybackMetrics(@NonNull String sessionId, PlaybackMetrics metrics) {
        try {
            mService.reportPlaybackMetrics(sessionId, metrics, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a playback session.
     */
    public PlaybackSession createSession() {
        try {
            String id = mService.getSessionId(mUserId);
            PlaybackSession session = new PlaybackSession(id, this);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
