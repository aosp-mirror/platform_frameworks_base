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
 * limitations under the License.
 */

package com.google.android.startop.iorap;

import android.os.Parcelable;
import android.os.Parcel;

import android.annotation.NonNull;

/**
 * Uniquely identify an {@link com.google.android.startop.iorap.IIorap} method invocation,
 * used for asynchronous callbacks by the server. <br /><br />
 *
 * As all system server binder calls must be {@code oneway}, this means all invocations
 * into {@link com.google.android.startop.iorap.IIorap} are non-blocking. The request ID
 * exists to associate all calls with their respective callbacks in
 * {@link com.google.android.startop.iorap.ITaskListener}.
 *
 * @see com.google.android.startop.iorap.IIorap
 *
 * @hide
 */
public class RequestId implements Parcelable {

    public final long requestId;

    private static Object mLock = new Object();
    private static long mNextRequestId = 0;

    /**
     * Create a monotonically increasing request ID.<br /><br />
     *
     * It is invalid to re-use the same request ID for multiple method calls on
     * {@link com.google.android.startop.iorap.IIorap}; a new request ID must be created
     * each time.
     */
    @NonNull public static RequestId nextValueForSequence() {
        long currentRequestId;
        synchronized (mLock) {
            currentRequestId = mNextRequestId;
            ++mNextRequestId;
        }
        return new RequestId(currentRequestId);
    }

    private RequestId(long requestId) {
        this.requestId = requestId;

        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        if (requestId < 0) {
            throw new IllegalArgumentException("request id must be non-negative");
        }
    }

    @Override
    public String toString() {
        return String.format("{requestId: %d}", requestId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof RequestId) {
            return equals((RequestId) other);
        }
        return false;
    }

    private boolean equals(RequestId other) {
        return requestId == other.requestId;
    }


    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(requestId);
    }

    private RequestId(Parcel in) {
        requestId = in.readLong();

        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<RequestId> CREATOR
            = new Parcelable.Creator<RequestId>() {
        public RequestId createFromParcel(Parcel in) {
            return new RequestId(in);
        }

        public RequestId[] newArray(int size) {
            return new RequestId[size];
        }
    };
    //</editor-fold>
}
