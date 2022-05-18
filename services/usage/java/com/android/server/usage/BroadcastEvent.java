/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.usage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.util.LongArrayQueue;

import java.util.Objects;

/**
 * Contains the data needed to identify a broadcast event.
 */
class BroadcastEvent {
    private int mSourceUid;
    private String mTargetPackage;
    private int mTargetUserId;
    private long mIdForResponseEvent;
    private final LongArrayQueue mTimestampsMs;

    BroadcastEvent(int sourceUid, @NonNull String targetPackage, @UserIdInt int targetUserId,
            long idForResponseEvent) {
        mSourceUid = sourceUid;
        mTargetPackage = targetPackage;
        mTargetUserId = targetUserId;
        mIdForResponseEvent = idForResponseEvent;
        mTimestampsMs = new LongArrayQueue();
    }

    public int getSourceUid() {
        return mSourceUid;
    }

    public @NonNull String getTargetPackage() {
        return mTargetPackage;
    }

    public @UserIdInt int getTargetUserId() {
        return mTargetUserId;
    }

    public long getIdForResponseEvent() {
        return mIdForResponseEvent;
    }

    public LongArrayQueue getTimestampsMs() {
        return mTimestampsMs;
    }

    public void addTimestampMs(long timestampMs) {
        mTimestampsMs.addLast(timestampMs);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof BroadcastEvent)) {
            return false;
        }
        final BroadcastEvent other = (BroadcastEvent) obj;
        return this.mSourceUid == other.mSourceUid
                && this.mIdForResponseEvent == other.mIdForResponseEvent
                && this.mTargetUserId == other.mTargetUserId
                && this.mTargetPackage.equals(other.mTargetPackage);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSourceUid, mTargetPackage, mTargetUserId,
                mIdForResponseEvent);
    }

    @Override
    public @NonNull String toString() {
        return "BroadcastEvent {"
                + "srcUid=" + mSourceUid
                + ",tgtPkg=" + mTargetPackage
                + ",tgtUser=" + mTargetUserId
                + ",id=" + mIdForResponseEvent
                + "}";
    }
}
