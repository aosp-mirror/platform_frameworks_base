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
import android.annotation.SystemService;
import android.content.Context;
import android.os.PersistableBundle;
import android.os.RemoteException;

/**
 * This class gives information about, and interacts with media metrics.
 */
@SystemService(Context.MEDIA_METRICS_SERVICE)
public final class MediaMetricsManager {
    public static final long INVALID_TIMESTAMP = -1;

    private static final String TAG = "MediaMetricsManager";

    private IMediaMetricsManager mService;
    private int mUserId;

    /**
     * @hide
     */
    public MediaMetricsManager(IMediaMetricsManager service, int userId) {
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
     * Reports bundle metrics.
     * @hide
     */
    public void reportBundleMetrics(@NonNull String sessionId, PersistableBundle metrics) {
        try {
            mService.reportBundleMetrics(sessionId, metrics, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
    /**
     * Reports network event.
     * @hide
     */
    public void reportNetworkEvent(@NonNull String sessionId, NetworkEvent event) {
        try {
            mService.reportNetworkEvent(sessionId, event, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports playback state event.
     * @hide
     */
    public void reportPlaybackStateEvent(@NonNull String sessionId, PlaybackStateEvent event) {
        try {
            mService.reportPlaybackStateEvent(sessionId, event, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports track change event.
     * @hide
     */
    public void reportTrackChangeEvent(@NonNull String sessionId, TrackChangeEvent event) {
        try {
            mService.reportTrackChangeEvent(sessionId, event, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a playback session.
     */
    @NonNull
    public PlaybackSession createPlaybackSession() {
        try {
            String id = mService.getPlaybackSessionId(mUserId);
            PlaybackSession session = new PlaybackSession(id, this);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a recording session.
     */
    @NonNull
    public RecordingSession createRecordingSession() {
        try {
            String id = mService.getRecordingSessionId(mUserId);
            RecordingSession session = new RecordingSession(id, this);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a transcoding session.
     */
    @NonNull
    public TranscodingSession createTranscodingSession() {
        try {
            String id = mService.getTranscodingSessionId(mUserId);
            TranscodingSession session = new TranscodingSession(id, this);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a editing session.
     */
    @NonNull
    public EditingSession createEditingSession() {
        try {
            String id = mService.getEditingSessionId(mUserId);
            EditingSession session = new EditingSession(id, this);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a generic bundle session.
     */
    @NonNull
    public BundleSession createBundleSession() {
        try {
            String id = mService.getBundleSessionId(mUserId);
            BundleSession session = new BundleSession(id, this);
            return session;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a generic bundle session.
     */
    @NonNull
    public void releaseSessionId(@NonNull String sessionId) {
        try {
            mService.releaseSessionId(sessionId, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports error event.
     * @hide
     */
    public void reportPlaybackErrorEvent(@NonNull String sessionId, PlaybackErrorEvent event) {
        try {
            mService.reportPlaybackErrorEvent(sessionId, event, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
