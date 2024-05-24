/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.media.editing.flags.Flags.FLAG_ADD_MEDIA_METRICS_EDITING;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.util.AnnotationValidations;

import java.util.Objects;

/**
 * Represents a session of media editing, for example, transcoding between formats, transmuxing or
 * applying trimming or audio/video effects to a stream.
 */
public final class EditingSession implements AutoCloseable {
    private final @NonNull String mId;
    private final @NonNull MediaMetricsManager mManager;
    private final @NonNull LogSessionId mLogSessionId;

    /** @hide */
    public EditingSession(@NonNull String id, @NonNull MediaMetricsManager manager) {
        mId = id;
        mManager = manager;
        AnnotationValidations.validate(NonNull.class, null, mId);
        AnnotationValidations.validate(NonNull.class, null, mManager);
        mLogSessionId = new LogSessionId(mId);
    }

    /** Reports that an editing operation ended. */
    @FlaggedApi(FLAG_ADD_MEDIA_METRICS_EDITING)
    public void reportEditingEndedEvent(@NonNull EditingEndedEvent editingEndedEvent) {
        mManager.reportEditingEndedEvent(mId, editingEndedEvent);
    }

    /** Returns the identifier for logging this session. */
    public @NonNull LogSessionId getSessionId() {
        return mLogSessionId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EditingSession that = (EditingSession) o;
        return Objects.equals(mId, that.mId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId);
    }

    @Override
    public void close() {
        mManager.releaseSessionId(mLogSessionId.getStringId());
    }
}
