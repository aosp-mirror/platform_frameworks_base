/*
 * Copyright (C) 2023 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static java.util.Objects.requireNonNull;

import android.graphics.Rect;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * The parameter to create an overlay container that retrieved from
 * {@link android.app.ActivityOptions} bundle.
 */
class OverlayCreateParams {

    // TODO(b/295803704): Move them to WM Extensions so that we can reuse in WM Jetpack.
    @VisibleForTesting
    static final String KEY_OVERLAY_CREATE_PARAMS =
            "androidx.window.extensions.OverlayCreateParams";

    @VisibleForTesting
    static final String KEY_OVERLAY_CREATE_PARAMS_TASK_ID =
            "androidx.window.extensions.OverlayCreateParams.taskId";

    @VisibleForTesting
    static final String KEY_OVERLAY_CREATE_PARAMS_TAG =
            "androidx.window.extensions.OverlayCreateParams.tag";

    @VisibleForTesting
    static final String KEY_OVERLAY_CREATE_PARAMS_BOUNDS =
            "androidx.window.extensions.OverlayCreateParams.bounds";

    private final int mTaskId;

    @NonNull
    private final String mTag;

    @NonNull
    private final Rect mBounds;

    OverlayCreateParams(int taskId, @NonNull String tag, @NonNull Rect bounds) {
        mTaskId = taskId;
        mTag = requireNonNull(tag);
        mBounds = requireNonNull(bounds);
    }

    int getTaskId() {
        return mTaskId;
    }

    @NonNull
    String getTag() {
        return mTag;
    }

    @NonNull
    Rect getBounds() {
        return mBounds;
    }

    @Override
    public int hashCode() {
        int result = mTaskId;
        result = 31 * result + mTag.hashCode();
        result = 31 * result + mBounds.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof OverlayCreateParams thatParams)) return false;
        return mTaskId == thatParams.mTaskId
                && mTag.equals(thatParams.mTag)
                && mBounds.equals(thatParams.mBounds);
    }

    @Override
    public String toString() {
        return OverlayCreateParams.class.getSimpleName() + ": {"
                + "taskId=" + mTaskId
                + ", tag=" + mTag
                + ", bounds=" + mBounds
                + "}";
    }

    /** Retrieves the {@link OverlayCreateParams} from {@link android.app.ActivityOptions} bundle */
    @Nullable
    static OverlayCreateParams fromBundle(@NonNull Bundle bundle) {
        final Bundle paramsBundle = bundle.getBundle(KEY_OVERLAY_CREATE_PARAMS);
        if (paramsBundle == null) {
            return null;
        }
        final int taskId = paramsBundle.getInt(KEY_OVERLAY_CREATE_PARAMS_TASK_ID);
        final String tag = requireNonNull(paramsBundle.getString(KEY_OVERLAY_CREATE_PARAMS_TAG));
        final Rect bounds = requireNonNull(paramsBundle.getParcelable(
                KEY_OVERLAY_CREATE_PARAMS_BOUNDS, Rect.class));

        return new OverlayCreateParams(taskId, tag, bounds);
    }
}
