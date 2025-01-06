/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.job;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A simple wrapper which includes a timestamp (in millis since epoch)
 * and an array of {@link JobScheduler.PendingJobReason reasons} at that timestamp
 * for why a particular job may be pending.
 */
@FlaggedApi(Flags.FLAG_GET_PENDING_JOB_REASONS_HISTORY_API)
public final class PendingJobReasonsInfo implements Parcelable {

    @CurrentTimeMillisLong
    private final long mTimestampMillis;

    @NonNull
    @JobScheduler.PendingJobReason
    private final int[] mPendingJobReasons;

    public PendingJobReasonsInfo(long timestampMillis,
            @NonNull @JobScheduler.PendingJobReason int[] reasons) {
        mTimestampMillis = timestampMillis;
        mPendingJobReasons = reasons;
    }

    /**
     * @return the time (in millis since epoch) associated with the set of pending job reasons.
     */
    @CurrentTimeMillisLong
    public long getTimestampMillis() {
        return mTimestampMillis;
    }

    /**
     * Returns a set of {@link android.app.job.JobScheduler.PendingJobReason reasons} representing
     * why the job may not have executed at the associated timestamp.
     * <p>
     * These reasons could either be explicitly set constraints on the job or implicit
     * constraints imposed by the system due to various reasons.
     * <p>
     * Note: if the only {@link android.app.job.JobScheduler.PendingJobReason} present is
     * {@link JobScheduler.PendingJobReason#PENDING_JOB_REASON_UNDEFINED}, it could mean
     * that the job was ready to be executed at that time.
     */
    @NonNull
    @JobScheduler.PendingJobReason
    public int[] getPendingJobReasons() {
        return mPendingJobReasons;
    }

    private PendingJobReasonsInfo(Parcel in) {
        mTimestampMillis = in.readLong();
        mPendingJobReasons = in.createIntArray();
    }

    @NonNull
    public static final Creator<PendingJobReasonsInfo> CREATOR =
            new Creator<>() {
                @Override
                public PendingJobReasonsInfo createFromParcel(Parcel in) {
                    return new PendingJobReasonsInfo(in);
                }

                @Override
                public PendingJobReasonsInfo[] newArray(int size) {
                    return new PendingJobReasonsInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeLong(mTimestampMillis);
        dest.writeIntArray(mPendingJobReasons);
    }
}
