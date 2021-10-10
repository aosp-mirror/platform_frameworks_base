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

import android.app.job.JobParameters;
import android.annotation.NonNull;
import android.os.Parcelable;
import android.os.Parcel;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Forward JobService events to iorapd. <br /><br />
 *
 * iorapd sometimes need to use background jobs. Forwarding these events to iorapd
 * notifies iorapd when it is an opportune time to execute these background jobs.
 *
 * @hide
 */
public class JobScheduledEvent implements Parcelable {

    /** JobService#onJobStarted */
    public static final int TYPE_START_JOB = 0;
    /** JobService#onJobStopped */
    public static final int TYPE_STOP_JOB = 1;
    private static final int TYPE_MAX = 1;

    /** @hide */
    @IntDef(flag = true, prefix = { "TYPE_" }, value = {
            TYPE_START_JOB,
            TYPE_STOP_JOB,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    @Type public final int type;

    /** @see JobParameters#getJobId() */
    public final int jobId;

    public final String packageName;

    public final boolean shouldUpdateVersions;

    /** Device is 'idle' and it's charging (plugged in). */
    public static final int SORT_IDLE_MAINTENANCE = 0;
    private static final int SORT_MAX = 0;

    /** @hide */
    @IntDef(flag = true, prefix = { "SORT_" }, value = {
            SORT_IDLE_MAINTENANCE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Sort {}

    /**
     * Roughly corresponds to the {@code extras} fields in a JobParameters.
     */
    @Sort public final int sort;

    /**
     * Creates a {@link #SORT_IDLE_MAINTENANCE} event from the type and job parameters.
     *
     * Only the job ID is retained from {@code jobParams}, all other param info is dropped.
     */
    @NonNull
    public static JobScheduledEvent createIdleMaintenance(
        @Type int type, JobParameters jobParams, String packageName, boolean shouldUpdateVersions) {
        return new JobScheduledEvent(
            type, jobParams.getJobId(), SORT_IDLE_MAINTENANCE, packageName, shouldUpdateVersions);
    }

    private JobScheduledEvent(@Type int type,
                              int jobId,
                              @Sort int sort,
                              String packageName,
                              boolean shouldUpdateVersions) {
        this.type = type;
        this.jobId = jobId;
        this.sort = sort;
        this.packageName = packageName;
        this.shouldUpdateVersions = shouldUpdateVersions;

        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        CheckHelpers.checkTypeInRange(type, TYPE_MAX);
        // No check for 'jobId': any int is valid.
        CheckHelpers.checkTypeInRange(sort, SORT_MAX);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof JobScheduledEvent) {
            return equals((JobScheduledEvent) other);
        }
        return false;
    }

    private boolean equals(JobScheduledEvent other) {
        return type == other.type &&
                jobId == other.jobId &&
                sort == other.sort &&
                packageName.equals(other.packageName) &&
                shouldUpdateVersions == other.shouldUpdateVersions;
    }

    @Override
    public String toString() {
        return String.format(
            "{type: %d, jobId: %d, sort: %d, packageName: %s, shouldUpdateVersions %b}",
            type, jobId, sort, packageName, shouldUpdateVersions);
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(type);
        out.writeInt(jobId);
        out.writeInt(sort);
        out.writeString(packageName);
        out.writeBoolean(shouldUpdateVersions);

        // We do not parcel the entire JobParameters here because there is no C++ equivalent
        // of that class [which the iorapd side of the binder interface requires].
    }

    private JobScheduledEvent(Parcel in) {
        this.type = in.readInt();
        this.jobId = in.readInt();
        this.sort = in.readInt();
        this.packageName = in.readString();
        this.shouldUpdateVersions = in.readBoolean();

        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<JobScheduledEvent> CREATOR
            = new Parcelable.Creator<JobScheduledEvent>() {
        public JobScheduledEvent createFromParcel(Parcel in) {
            return new JobScheduledEvent(in);
        }

        public JobScheduledEvent[] newArray(int size) {
            return new JobScheduledEvent[size];
        }
    };
    //</editor-fold>
}
