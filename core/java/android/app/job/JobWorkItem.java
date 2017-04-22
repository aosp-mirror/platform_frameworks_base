/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A unit of work that can be enqueued for a job using
 * {@link JobScheduler#enqueue JobScheduler.enqueue}.  See
 * {@link JobParameters#dequeueWork() JobParameters.dequeueWork} for more details.
 */
final public class JobWorkItem implements Parcelable {
    final Intent mIntent;
    int mDeliveryCount;
    int mWorkId;
    Object mGrants;

    /**
     * Create a new piece of work, which can be submitted to
     * {@link JobScheduler#enqueue JobScheduler.enqueue}.
     *
     * @param intent The general Intent describing this work.
     */
    public JobWorkItem(Intent intent) {
        mIntent = intent;
    }

    /**
     * Return the Intent associated with this work.
     */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Return the count of the number of times this work item has been delivered
     * to the job.  The value will be > 1 if it has been redelivered because the job
     * was stopped or crashed while it had previously been delivered but before the
     * job had called {@link JobParameters#completeWork JobParameters.completeWork} for it.
     */
    public int getDeliveryCount() {
        return mDeliveryCount;
    }

    /**
     * @hide
     */
    public void bumpDeliveryCount() {
        mDeliveryCount++;
    }

    /**
     * @hide
     */
    public void setWorkId(int id) {
        mWorkId = id;
    }

    /**
     * @hide
     */
    public int getWorkId() {
        return mWorkId;
    }

    /**
     * @hide
     */
    public void setGrants(Object grants) {
        mGrants = grants;
    }

    /**
     * @hide
     */
    public Object getGrants() {
        return mGrants;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("JobWorkItem{id=");
        sb.append(mWorkId);
        sb.append(" intent=");
        sb.append(mIntent);
        if (mDeliveryCount != 0) {
            sb.append(" dcount=");
            sb.append(mDeliveryCount);
        }
        sb.append("}");
        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel out, int flags) {
        if (mIntent != null) {
            out.writeInt(1);
            mIntent.writeToParcel(out, 0);
        } else {
            out.writeInt(0);
        }
        out.writeInt(mDeliveryCount);
        out.writeInt(mWorkId);
    }

    public static final Parcelable.Creator<JobWorkItem> CREATOR
            = new Parcelable.Creator<JobWorkItem>() {
        public JobWorkItem createFromParcel(Parcel in) {
            return new JobWorkItem(in);
        }

        public JobWorkItem[] newArray(int size) {
            return new JobWorkItem[size];
        }
    };

    JobWorkItem(Parcel in) {
        if (in.readInt() != 0) {
            mIntent = Intent.CREATOR.createFromParcel(in);
        } else {
            mIntent = null;
        }
        mDeliveryCount = in.readInt();
        mWorkId = in.readInt();
    }
}
