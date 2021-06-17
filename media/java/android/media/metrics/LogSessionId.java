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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;

import java.util.Objects;

/**
 * An instances of this class represents the ID of a log session.
 */
public final class LogSessionId {
    @NonNull private final String mSessionId;

    /**
     * A {@link LogSessionId} object which is used to clear any existing session ID.
     */
    @NonNull public static final LogSessionId LOG_SESSION_ID_NONE = new LogSessionId("");

    /** @hide */
    @TestApi
    public LogSessionId(@NonNull String id) {
        mSessionId = Objects.requireNonNull(id);
    }

    /** Returns the ID represented by a string. */
    @NonNull
    public String getStringId() {
        return mSessionId;
    }

    @Override
    public String toString() {
        return mSessionId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LogSessionId that = (LogSessionId) o;
        return Objects.equals(mSessionId, that.mSessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSessionId);
    }
}
