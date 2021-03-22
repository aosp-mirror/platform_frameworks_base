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
import android.annotation.TestApi;

/**
 * An instances of this class represents the ID of a log session.
 */
public final class LogSessionId {
    private final String mSessionId;

    /* package */ LogSessionId(@NonNull String id) {
        mSessionId = id;
    }

    /** @hide */
    @TestApi
    @NonNull
    public String getStringId() {
        return mSessionId;
    }

    @Override
    public String toString() {
        return mSessionId;
    }
}
