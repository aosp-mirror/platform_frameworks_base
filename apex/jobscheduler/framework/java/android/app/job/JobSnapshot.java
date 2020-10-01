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

package android.app.job;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Current-state snapshot of a scheduled job.  These snapshots are not used in apps;
 * they exist only within the system process across the local call surface where JobStatus
 * is not directly accessible at build time.
 *
 * Constraints that the underlying job does not require are always reported as
 * being currently satisfied.
 * @hide
 */
public class JobSnapshot implements Parcelable {
    private final JobInfo mJob;
    private final int mSatisfiedConstraints;
    private final boolean mIsRunnable;

    public JobSnapshot(JobInfo info, int satisfiedMask, boolean runnable) {
        mJob = info;
        mSatisfiedConstraints = satisfiedMask;
        mIsRunnable = runnable;
    }

    public JobSnapshot(Parcel in) {
        mJob = JobInfo.CREATOR.createFromParcel(in);
        mSatisfiedConstraints = in.readInt();
        mIsRunnable = in.readBoolean();
    }

    private boolean satisfied(int flag) {
        return (mSatisfiedConstraints & flag) != 0;
    }

    /**
     * Returning JobInfo bound to this snapshot
     * @return JobInfo of this snapshot
     */
    public JobInfo getJobInfo() {
        return mJob;
    }

    /**
     * Is this job actually runnable at this moment?
     */
    public boolean isRunnable() {
        return mIsRunnable;
    }

    /**
     * @see JobInfo.Builder#setRequiresCharging(boolean)
     */
    public boolean isChargingSatisfied() {
        return !mJob.isRequireCharging()
                || satisfied(JobInfo.CONSTRAINT_FLAG_CHARGING);
    }

    /**
     * @see JobInfo.Builder#setRequiresBatteryNotLow(boolean)
     */
    public boolean isBatteryNotLowSatisfied() {
        return !mJob.isRequireBatteryNotLow()
                || satisfied(JobInfo.CONSTRAINT_FLAG_BATTERY_NOT_LOW);
    }

    /**
     * @see JobInfo.Builder#setRequiresDeviceIdle(boolean)
     */
    public boolean isRequireDeviceIdleSatisfied() {
        return !mJob.isRequireDeviceIdle()
                || satisfied(JobInfo.CONSTRAINT_FLAG_DEVICE_IDLE);
    }

    public boolean isRequireStorageNotLowSatisfied() {
        return !mJob.isRequireStorageNotLow()
                || satisfied(JobInfo.CONSTRAINT_FLAG_STORAGE_NOT_LOW);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        mJob.writeToParcel(out, flags);
        out.writeInt(mSatisfiedConstraints);
        out.writeBoolean(mIsRunnable);
    }

    public static final @android.annotation.NonNull Creator<JobSnapshot> CREATOR = new Creator<JobSnapshot>() {
        @Override
        public JobSnapshot createFromParcel(Parcel in) {
            return new JobSnapshot(in);
        }

        @Override
        public JobSnapshot[] newArray(int size) {
            return new JobSnapshot[size];
        }
    };
}
