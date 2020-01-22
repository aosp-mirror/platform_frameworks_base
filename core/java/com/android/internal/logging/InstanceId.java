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

package com.android.internal.logging;

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * An opaque identifier used to disambiguate which logs refer to a particular instance of some
 * UI element. Useful when there might be multiple instances simultaneously active.
 * Obtain from InstanceIdSequence.
 */
public class InstanceId {
    private int mId;
    protected InstanceId(int id) {
        mId = id;
    }
    @VisibleForTesting
    public int getId() {
        return mId;
    }

    @Override
    public int hashCode() {
        return mId;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof InstanceId)) {
            return false;
        }
        return mId == ((InstanceId) obj).mId;
    }
}
