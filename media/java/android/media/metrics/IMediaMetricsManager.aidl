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

import android.media.metrics.NetworkEvent;
import android.media.metrics.PlaybackErrorEvent;
import android.media.metrics.PlaybackMetrics;
import android.media.metrics.PlaybackStateEvent;
import android.media.metrics.TrackChangeEvent;

/**
 * Interface to the playback manager service.
 * @hide
 */
interface IMediaMetricsManager {
    void reportPlaybackMetrics(in String sessionId, in PlaybackMetrics metrics, int userId);
    String getPlaybackSessionId(int userId);
    String getRecordingSessionId(int userId);
    void reportNetworkEvent(in String sessionId, in NetworkEvent event, int userId);
    void reportPlaybackErrorEvent(in String sessionId, in PlaybackErrorEvent event, int userId);
    void reportPlaybackStateEvent(in String sessionId, in PlaybackStateEvent event, int userId);
    void reportTrackChangeEvent(in String sessionId, in TrackChangeEvent event, int userId);
}