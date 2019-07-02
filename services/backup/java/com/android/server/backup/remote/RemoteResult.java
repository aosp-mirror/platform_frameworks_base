/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.remote;

import android.annotation.IntDef;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Represents the result of a {@link RemoteCall}. It can be either {@link #FAILED_TIMED_OUT}, {@link
 * #FAILED_CANCELLED}, {@link #FAILED_THREAD_INTERRUPTED} or a successful result, in which case
 * {@link #get()} returns its value.
 *
 * <p>Use {@link #isPresent()} to check for successful result, or direct identity comparison to
 * check for specific failures, like {@code result == RemoteResult.FAILED_CANCELLED}.
 */
public class RemoteResult {
    public static final RemoteResult FAILED_TIMED_OUT = new RemoteResult(Type.FAILED_TIMED_OUT, 0);
    public static final RemoteResult FAILED_CANCELLED = new RemoteResult(Type.FAILED_CANCELLED, 0);
    public static final RemoteResult FAILED_THREAD_INTERRUPTED =
            new RemoteResult(Type.FAILED_THREAD_INTERRUPTED, 0);

    public static RemoteResult of(long value) {
        return new RemoteResult(Type.SUCCESS, value);
    }

    @Type private final int mType;
    private final long mValue;

    private RemoteResult(@Type int type, long value) {
        mType = type;
        mValue = value;
    }

    public boolean isPresent() {
        return mType == Type.SUCCESS;
    }

    /**
     * Returns the value of this result.
     *
     * @throws IllegalStateException in case this is not a successful result.
     */
    public long get() {
        Preconditions.checkState(isPresent(), "Can't obtain value of failed result");
        return mValue;
    }

    @Override
    public String toString() {
        return "RemoteResult{" + toStringDescription() + "}";
    }

    private String toStringDescription() {
        switch (mType) {
            case Type.SUCCESS:
                return Long.toString(mValue);
            case Type.FAILED_TIMED_OUT:
                return "FAILED_TIMED_OUT";
            case Type.FAILED_CANCELLED:
                return "FAILED_CANCELLED";
            case Type.FAILED_THREAD_INTERRUPTED:
                return "FAILED_THREAD_INTERRUPTED";
            default:
                throw new AssertionError("Unknown type");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RemoteResult)) {
            return false;
        }
        RemoteResult that = (RemoteResult) o;
        return mType == that.mType && mValue == that.mValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mType, mValue);
    }

    @IntDef({
        Type.SUCCESS,
        Type.FAILED_TIMED_OUT,
        Type.FAILED_CANCELLED,
        Type.FAILED_THREAD_INTERRUPTED
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface Type {
        int SUCCESS = 0;
        int FAILED_TIMED_OUT = 1;
        int FAILED_CANCELLED = 2;
        int FAILED_THREAD_INTERRUPTED = 3;
    }
}
