/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.contentcapture;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Holds metrics for content capture events flushing.
 *
 * @hide
 */
public final class FlushMetrics implements Parcelable {
    public int viewAppearedCount;
    public int viewDisappearedCount;
    public int viewTextChangedCount;
    public int sessionStarted;
    public int sessionFinished;

    /**
     * Resets all flush metrics.
     */
    public void reset() {
        viewAppearedCount = 0;
        viewDisappearedCount = 0;
        viewTextChangedCount = 0;
        sessionStarted = 0;
        sessionFinished = 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(sessionStarted);
        out.writeInt(sessionFinished);
        out.writeInt(viewAppearedCount);
        out.writeInt(viewDisappearedCount);
        out.writeInt(viewTextChangedCount);
    }

    @NonNull
    public static final Creator<FlushMetrics> CREATOR = new Creator<FlushMetrics>() {
        @NonNull
        @Override
        public FlushMetrics createFromParcel(Parcel in) {
            final FlushMetrics flushMetrics = new FlushMetrics();
            flushMetrics.sessionStarted = in.readInt();
            flushMetrics.sessionFinished = in.readInt();
            flushMetrics.viewAppearedCount = in.readInt();
            flushMetrics.viewDisappearedCount = in.readInt();
            flushMetrics.viewTextChangedCount = in.readInt();
            return flushMetrics;
        }

        @Override
        public FlushMetrics[] newArray(int size) {
            return new FlushMetrics[size];
        }
    };
}
