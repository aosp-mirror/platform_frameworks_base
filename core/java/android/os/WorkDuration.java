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

package android.os;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;

import java.util.Objects;

/**
 * WorkDuration contains the measured time in nano seconds of the workload
 * in each component, see
 * {@link PerformanceHintManager.Session#reportActualWorkDuration(WorkDuration)}.
 *
 * All timings should be in {@link SystemClock#uptimeNanos()} and measured in wall time.
 */
@FlaggedApi(Flags.FLAG_ADPF_GPU_REPORT_ACTUAL_WORK_DURATION)
public final class WorkDuration implements Parcelable {
    long mWorkPeriodStartTimestampNanos = 0;
    long mActualTotalDurationNanos = 0;
    long mActualCpuDurationNanos = 0;
    long mActualGpuDurationNanos = 0;
    long mTimestampNanos = 0;

    public static final @NonNull Creator<WorkDuration> CREATOR = new Creator<>() {
        @Override
        public WorkDuration createFromParcel(Parcel in) {
            return new WorkDuration(in);
        }

        @Override
        public WorkDuration[] newArray(int size) {
            return new WorkDuration[size];
        }
    };

    public WorkDuration() {}

    /**
     * Constructor for testing.
     *
     * @hide
     */
    public WorkDuration(long workPeriodStartTimestampNanos,
                      long actualTotalDurationNanos,
                      long actualCpuDurationNanos,
                      long actualGpuDurationNanos,
                      long timestampNanos) {
        mWorkPeriodStartTimestampNanos = workPeriodStartTimestampNanos;
        mActualTotalDurationNanos = actualTotalDurationNanos;
        mActualCpuDurationNanos = actualCpuDurationNanos;
        mActualGpuDurationNanos = actualGpuDurationNanos;
        mTimestampNanos = timestampNanos;
    }

    WorkDuration(@NonNull Parcel in) {
        mWorkPeriodStartTimestampNanos = in.readLong();
        mActualTotalDurationNanos = in.readLong();
        mActualCpuDurationNanos = in.readLong();
        mActualGpuDurationNanos = in.readLong();
        mTimestampNanos = in.readLong();
    }

    /**
     * Sets the work period start timestamp in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public void setWorkPeriodStartTimestampNanos(long workPeriodStartTimestampNanos) {
        if (workPeriodStartTimestampNanos <= 0) {
            throw new IllegalArgumentException(
                "the work period start timestamp should be greater than zero.");
        }
        mWorkPeriodStartTimestampNanos = workPeriodStartTimestampNanos;
    }

    /**
     * Sets the actual total duration in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public void setActualTotalDurationNanos(long actualTotalDurationNanos) {
        if (actualTotalDurationNanos <= 0) {
            throw new IllegalArgumentException(
                "the actual total duration should be greater than zero.");
        }
        mActualTotalDurationNanos = actualTotalDurationNanos;
    }

    /**
     * Sets the actual CPU duration in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public void setActualCpuDurationNanos(long actualCpuDurationNanos) {
        if (actualCpuDurationNanos < 0) {
            throw new IllegalArgumentException(
                "the actual CPU duration should be greater than or equal to zero.");
        }
        mActualCpuDurationNanos = actualCpuDurationNanos;
    }

    /**
     * Sets the actual GPU duration in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public void setActualGpuDurationNanos(long actualGpuDurationNanos) {
        if (actualGpuDurationNanos < 0) {
            throw new IllegalArgumentException(
                "the actual GPU duration should be greater than or equal to zero.");
        }
        mActualGpuDurationNanos = actualGpuDurationNanos;
    }

    /**
     * Returns the work period start timestamp based in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public long getWorkPeriodStartTimestampNanos() {
        return mWorkPeriodStartTimestampNanos;
    }

    /**
     * Returns the actual total duration in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public long getActualTotalDurationNanos() {
        return mActualTotalDurationNanos;
    }

    /**
     * Returns the actual CPU duration in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public long getActualCpuDurationNanos() {
        return mActualCpuDurationNanos;
    }

    /**
     * Returns the actual GPU duration in nanoseconds.
     *
     * All timings should be in {@link SystemClock#uptimeNanos()}.
     */
    public long getActualGpuDurationNanos() {
        return mActualGpuDurationNanos;
    }

    /**
     * @hide
     */
    public long getTimestampNanos() {
        return mTimestampNanos;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mWorkPeriodStartTimestampNanos);
        dest.writeLong(mActualTotalDurationNanos);
        dest.writeLong(mActualCpuDurationNanos);
        dest.writeLong(mActualGpuDurationNanos);
        dest.writeLong(mTimestampNanos);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof WorkDuration)) {
            return false;
        }
        WorkDuration workDuration = (WorkDuration) obj;
        return workDuration.mTimestampNanos == this.mTimestampNanos
            && workDuration.mWorkPeriodStartTimestampNanos == this.mWorkPeriodStartTimestampNanos
            && workDuration.mActualTotalDurationNanos == this.mActualTotalDurationNanos
            && workDuration.mActualCpuDurationNanos == this.mActualCpuDurationNanos
            && workDuration.mActualGpuDurationNanos == this.mActualGpuDurationNanos;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mWorkPeriodStartTimestampNanos, mActualTotalDurationNanos,
                            mActualCpuDurationNanos, mActualGpuDurationNanos, mTimestampNanos);
    }
}
