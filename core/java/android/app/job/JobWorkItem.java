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
 * {@link JobScheduler#enqueue JobScheduler.enqueue}.
 */
final public class JobWorkItem implements Parcelable {
    final Intent mIntent;
    int mWorkId;
    Object mGrants;

    /**
     * Create a new piece of work.
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
        return "JobWorkItem{id=" + mWorkId + " intent=" + mIntent + "}";
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

    public JobWorkItem(Parcel in) {
        if (in.readInt() != 0) {
            mIntent = Intent.CREATOR.createFromParcel(in);
        } else {
            mIntent = null;
        }
        mWorkId = in.readInt();
    }
}
