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
import android.annotation.Nullable;

import com.android.internal.util.AnnotationValidations;

import java.util.Objects;

/**
 * An instances of this class represents a session of media playback.
 */
public final class PlaybackSession implements AutoCloseable {
    private final @NonNull String mId;
    private final @NonNull MediaMetricsManager mManager;
    private final @NonNull LogSessionId mLogSessionId;
    private boolean mClosed = false;

    /**
     * Creates a new PlaybackSession.
     *
     * @hide
     */
    public PlaybackSession(@NonNull String id, @NonNull MediaMetricsManager manager) {
        mId = id;
        mManager = manager;
        AnnotationValidations.validate(NonNull.class, null, mId);
        AnnotationValidations.validate(NonNull.class, null, mManager);
        mLogSessionId = new LogSessionId(mId);
    }

    /**
     * Reports playback metrics.
     */
    public void reportPlaybackMetrics(@NonNull PlaybackMetrics metrics) {
        mManager.reportPlaybackMetrics(mId, metrics);
    }

    /**
     * Reports error event.
     */
    public void reportPlaybackErrorEvent(@NonNull PlaybackErrorEvent event) {
        mManager.reportPlaybackErrorEvent(mId, event);
    }

    /**
     * Reports network event.
     */
    public void reportNetworkEvent(@NonNull NetworkEvent event) {
        mManager.reportNetworkEvent(mId, event);
    }

    /**
     * Reports playback state event.
     */
    public void reportPlaybackStateEvent(@NonNull PlaybackStateEvent event) {
        mManager.reportPlaybackStateEvent(mId, event);
    }

    /**
     * Reports track change event.
     */
    public void reportTrackChangeEvent(@NonNull TrackChangeEvent event) {
        mManager.reportTrackChangeEvent(mId, event);
    }

    public @NonNull String getId() {
        // TODO: remove this method and use getSessionId();
        return mId;
    }

    /** @hide */
    public @NonNull LogSessionId getSessionId() {
        // TODO: remove getId() and use this method;
        return mLogSessionId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlaybackSession that = (PlaybackSession) o;
        return Objects.equals(mId, that.mId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId);
    }

    @Override
    public void close() {
        mClosed = true;
    }
}
